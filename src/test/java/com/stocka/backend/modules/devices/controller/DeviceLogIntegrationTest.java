package com.stocka.backend.modules.devices.controller;

import static com.stocka.backend.modules.organizations.IntegrationTestSupport.ADMIN_EMAIL;
import static com.stocka.backend.modules.organizations.IntegrationTestSupport.ADMIN_PASSWORD;
import static com.stocka.backend.modules.organizations.IntegrationTestSupport.buildMockMvc;
import static com.stocka.backend.modules.organizations.IntegrationTestSupport.login;
import static com.stocka.backend.modules.organizations.IntegrationTestSupport.signupAndLogin;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.context.WebApplicationContext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocka.backend.modules.devices.service.DeviceLogService;
import com.stocka.backend.modules.organizations.IntegrationTestSupport;
import com.stocka.backend.modules.storage.R2Service;

/**
 * End-to-end coverage of the players' log kept in Stocka: the player uploads it itself with its
 * heartbeat token ({@code /player/logs}), the belén app carries it by the player's uuid
 * ({@code /devices/{uuid}/logs}) and the frontend reads it ({@code /organizations/{org}/devices/{id}/logs}).
 *
 * <p>Fixture: the seeded global admin owns "Acme" with one registered device bound to {@link #UUID}
 * by a heartbeat; Acme also has a USER and a SPECTATOR member, and an outsider owns "Beta". R2 is the
 * local fallback of the test profile. Rate limiting is disabled; its mapping is covered by
 * {@code RateLimitFilterTest}.
 */
@SpringBootTest
@DisplayName("Device logs (integration)")
class DeviceLogIntegrationTest {

    private static final String UUID = "6f1c2a9e-4b7d-4e21-9c3a-0d5e8f7b1a42";
    private static final String TODAY_FILE = "system-" + LocalDate.now() + ".log";
    private static final String L1 = "2026-10-26 18:30:02 | core | INFO     | arrencada\n";
    private static final String L2 = "2026-10-26 18:31:10 | api | INFO     | [Joan] play\n";
    private static final String L3 = "2026-10-26 18:33:45 | motors | WARNING  | Angel max_time\n";

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DeviceLogService logService;
    @Autowired private R2Service r2;

    private final ObjectMapper om = new ObjectMapper();

    private MockMvc mockMvc;
    private String adminToken;
    private String userToken;
    private String spectatorToken;
    private String outsiderToken;
    private String acmeSlug;
    private Integer deviceRowId;
    private String deviceToken;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = buildMockMvc(context);
        IntegrationTestSupport.cleanDatabase(jdbcTemplate);

        adminToken = login(mockMvc, om, ADMIN_EMAIL, ADMIN_PASSWORD);
        acmeSlug = createOrg(adminToken, "Acme", "acme");
        JsonNode created = createDevice(adminToken, acmeSlug, "Pessebre parròquia");
        deviceRowId = created.get("device").get("id").asInt();
        deviceToken = created.get("heartbeatToken").asText();
        mockMvc.perform(post("/player/heartbeat")
                        .header("Authorization", "Device " + deviceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("deviceId", UUID, "state", "IDLE"))))
                .andExpect(status().isNoContent());

        outsiderToken = signupAndLogin(mockMvc, om, jdbcTemplate, "out@test.com", "outsider");
        createOrg(outsiderToken, "Beta", "beta");
        userToken = signupAndLogin(mockMvc, om, jdbcTemplate, "user@test.com", "user");
        spectatorToken = signupAndLogin(mockMvc, om, jdbcTemplate, "spectator@test.com", "spectator");
        Integer acmeId = jdbcTemplate.queryForObject("SELECT id FROM organizations WHERE slug = ?", Integer.class, "acme");
        addMember(acmeId, userIdOf("user@test.com"), "USER");
        addMember(acmeId, userIdOf("spectator@test.com"), "SPECTATOR");
    }

    @Nested
    @DisplayName("the player uploads its own log")
    class Player {

        @Test
        @DisplayName("the cursor of a new device is empty and says the oldest day kept (180 days)")
        void emptyCursor() throws Exception {
            mockMvc.perform(get("/player/logs/cursor").header("Authorization", "Device " + deviceToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.files").isEmpty())
                    .andExpect(jsonPath("$.since").value(LocalDate.now().minusDays(180).toString()));
        }

        @Test
        @DisplayName("pieces append in order and the cursor follows the end of each file")
        void appendsInOrder() throws Exception {
            playerAppend(TODAY_FILE, 0, L1).andExpect(status().isOk())
                    .andExpect(jsonPath("$.file").value(TODAY_FILE))
                    .andExpect(jsonPath("$.end").value(bytes(L1)))
                    .andExpect(jsonPath("$.stored").value(bytes(L1)));
            playerAppend(TODAY_FILE, bytes(L1), L2 + L3).andExpect(status().isOk())
                    .andExpect(jsonPath("$.end").value(bytes(L1 + L2 + L3)))
                    .andExpect(jsonPath("$.stored").value(bytes(L2 + L3)));
            playerAppend("system-2026-10-25.log", 0, L1).andExpect(status().isOk());

            mockMvc.perform(get("/player/logs/cursor").header("Authorization", "Device " + deviceToken))
                    .andExpect(jsonPath("$.files['" + TODAY_FILE + "']").value(bytes(L1 + L2 + L3)))
                    .andExpect(jsonPath("$.files['system-2026-10-25.log']").value(bytes(L1)));
            assertThat(fileText(TODAY_FILE, null)).isEqualTo(L1 + L2 + L3);
        }

        @Test
        @DisplayName("a piece that overlaps what is stored only adds the new bytes")
        void trimsOverlap() throws Exception {
            playerAppend(TODAY_FILE, 0, L1 + L2).andExpect(status().isOk());

            playerAppend(TODAY_FILE, bytes(L1), L2 + L3).andExpect(status().isOk())
                    .andExpect(jsonPath("$.end").value(bytes(L1 + L2 + L3)))
                    .andExpect(jsonPath("$.stored").value(bytes(L3)));

            assertThat(fileText(TODAY_FILE, null)).isEqualTo(L1 + L2 + L3);
            assertThat(chunkCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("a piece already stored is accepted without storing anything")
        void duplicateIsIdempotent() throws Exception {
            playerAppend(TODAY_FILE, 0, L1 + L2).andExpect(status().isOk());

            playerAppend(TODAY_FILE, 0, L1).andExpect(status().isOk())
                    .andExpect(jsonPath("$.end").value(bytes(L1 + L2)))
                    .andExpect(jsonPath("$.stored").value(0));

            assertThat(chunkCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("a piece that would leave a gap is 409 with the offset to resume from")
        void gapIsConflict() throws Exception {
            playerAppend(TODAY_FILE, 0, L1).andExpect(status().isOk());

            playerAppend(TODAY_FILE, bytes(L1) + 10, L2)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("devices.log_offset_mismatch"))
                    .andExpect(jsonPath("$.params.expected").value(bytes(L1)));
        }

        @Test
        @DisplayName("an empty body stores nothing")
        void emptyBody() throws Exception {
            playerAppend(TODAY_FILE, 0, "").andExpect(status().isOk())
                    .andExpect(jsonPath("$.end").value(0))
                    .andExpect(jsonPath("$.stored").value(0));
            assertThat(chunkCount()).isZero();
        }

        @Test
        @DisplayName("file names with directories, another extension or a leading dot are refused")
        void badNames() throws Exception {
            for (String name : new String[] {"../system.log", "logs/system.log", "system.txt", ".hidden.log", "a b.log"}) {
                playerAppend(name, 0, L1)
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code").value("devices.log_file_invalid"));
            }
        }

        @Test
        @DisplayName("a negative offset is refused")
        void negativeOffset() throws Exception {
            playerAppend(TODAY_FILE, -1, L1)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("devices.log_file_invalid"));
        }

        @Test
        @DisplayName("a piece larger than max-chunk-bytes is 413 and stores nothing")
        void tooLarge() throws Exception {
            byte[] big = new byte[2 * 1024 * 1024 + 1];
            java.util.Arrays.fill(big, (byte) 'x');
            mockMvc.perform(post("/player/logs").param("file", TODAY_FILE).param("from", "0")
                            .header("Authorization", "Device " + deviceToken)
                            .contentType(MediaType.TEXT_PLAIN)
                            .content(big))
                    .andExpect(status().isPayloadTooLarge())
                    .andExpect(jsonPath("$.code").value("devices.log_too_large"))
                    .andExpect(jsonPath("$.params.max").value(2 * 1024 * 1024));
            assertThat(chunkCount()).isZero();
        }

        @Test
        @DisplayName("without a valid device token both endpoints are 401")
        void badToken() throws Exception {
            for (String auth : new String[] {null, "Device bht_nope", "Bearer " + adminToken}) {
                var cursor = get("/player/logs/cursor");
                var append = post("/player/logs").param("file", TODAY_FILE).param("from", "0").content(L1);
                if (auth != null) {
                    cursor = cursor.header("Authorization", auth);
                    append = append.header("Authorization", auth);
                }
                mockMvc.perform(cursor).andExpect(status().isUnauthorized())
                        .andExpect(jsonPath("$.code").value("devices.token_invalid"));
                mockMvc.perform(append).andExpect(status().isUnauthorized());
            }
        }

        @Test
        @DisplayName("a rotated token stops working at once")
        void rotatedToken() throws Exception {
            mockMvc.perform(post("/organizations/" + acmeSlug + "/devices/" + deviceRowId + "/rotate-token")
                    .header("Authorization", "Bearer " + adminToken)).andExpect(status().isOk());

            playerAppend(TODAY_FILE, 0, L1).andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("each piece records its day, lines, first and last time and that the player sent it")
        void pieceMetadata() throws Exception {
            playerAppend("system-2026-10-26.log", 0, L1 + L2 + L3).andExpect(status().isOk());

            Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM device_log_chunks");
            assertThat(row.get("LOG_DAY").toString()).isEqualTo("2026-10-26");
            assertThat(((Number) row.get("LINE_COUNT")).intValue()).isEqualTo(3);
            assertThat(row.get("FIRST_AT").toString()).startsWith("2026-10-26 18:30:02");
            assertThat(row.get("LAST_AT").toString()).startsWith("2026-10-26 18:33:45");
            assertThat(row.get("SOURCE")).isEqualTo("DEVICE");
            assertThat(row.get("UPLOADED_BY")).isNull();
            assertThat(r2.exists((String) row.get("STORAGE_KEY"))).isTrue();
        }

        @Test
        @DisplayName("a file without a date in its name takes the day of its first line")
        void dayFromContent() throws Exception {
            playerAppend("player.log", 0, L1).andExpect(status().isOk());

            assertThat(jdbcTemplate.queryForObject("SELECT log_day FROM device_log_chunks", String.class)).isEqualTo("2026-10-26");
        }
    }

    @Nested
    @DisplayName("the belén app carries the log")
    class Relay {

        @Test
        @DisplayName("a USER member reads the cursor and appends by the player's uuid; the piece records who carried it")
        void userCarries() throws Exception {
            relayAppend(userToken, UUID, TODAY_FILE, 0, L1).andExpect(status().isOk())
                    .andExpect(jsonPath("$.end").value(bytes(L1)));

            mockMvc.perform(get("/devices/" + UUID + "/logs/cursor").header("Authorization", "Bearer " + userToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.files['" + TODAY_FILE + "']").value(bytes(L1)));
            Map<String, Object> row = jdbcTemplate.queryForMap("SELECT source, uploaded_by FROM device_log_chunks");
            assertThat(row.get("SOURCE")).isEqualTo("USER");
            assertThat(((Number) row.get("UPLOADED_BY")).intValue()).isEqualTo(userIdOf("user@test.com"));
        }

        @Test
        @DisplayName("the player and the app share the file: what one sent is not stored twice")
        void playerAndAppShare() throws Exception {
            playerAppend(TODAY_FILE, 0, L1 + L2).andExpect(status().isOk());

            relayAppend(userToken, UUID, TODAY_FILE, 0, L1 + L2 + L3).andExpect(status().isOk())
                    .andExpect(jsonPath("$.stored").value(bytes(L3)));

            assertThat(fileText(TODAY_FILE, null)).isEqualTo(L1 + L2 + L3);
        }

        @Test
        @DisplayName("SPECTATOR, outsiders and unknown uuids get 404 and store nothing")
        void notControllable() throws Exception {
            relayAppend(spectatorToken, UUID, TODAY_FILE, 0, L1).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("devices.not_found"));
            relayAppend(outsiderToken, UUID, TODAY_FILE, 0, L1).andExpect(status().isNotFound());
            relayAppend(userToken, "00000000-0000-4000-8000-000000000000", TODAY_FILE, 0, L1).andExpect(status().isNotFound());
            mockMvc.perform(get("/devices/" + UUID + "/logs/cursor").header("Authorization", "Bearer " + spectatorToken))
                    .andExpect(status().isNotFound());
            assertThat(chunkCount()).isZero();
        }

        @Test
        @DisplayName("without a session it is 401")
        void anonymous() throws Exception {
            mockMvc.perform(get("/devices/" + UUID + "/logs/cursor")).andExpect(status().isUnauthorized());
            mockMvc.perform(post("/devices/" + UUID + "/logs").param("file", TODAY_FILE).param("from", "0").content(L1))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("a soft-deleted device no longer receives the log")
        void deletedDevice() throws Exception {
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .delete("/organizations/" + acmeSlug + "/devices/" + deviceRowId)
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isNoContent());

            relayAppend(userToken, UUID, TODAY_FILE, 0, L1).andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("the frontend reads the log")
    class Read {

        @Test
        @DisplayName("lists the stored files newest day first with size, lines, times and the last upload")
        void listsFiles() throws Exception {
            playerAppend("system-2026-10-25.log", 0, L1).andExpect(status().isOk());
            playerAppend("system-2026-10-26.log", 0, L1 + L2).andExpect(status().isOk());
            playerAppend("system-2026-10-26.log", bytes(L1 + L2), L3).andExpect(status().isOk());

            mockMvc.perform(get(logsPath()).header("Authorization", "Bearer " + spectatorToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(2)))
                    .andExpect(jsonPath("$[0].file").value("system-2026-10-26.log"))
                    .andExpect(jsonPath("$[0].day").value("2026-10-26"))
                    .andExpect(jsonPath("$[0].size").value(bytes(L1 + L2 + L3)))
                    .andExpect(jsonPath("$[0].lines").value(3))
                    .andExpect(jsonPath("$[0].firstAt").value("2026-10-26T18:30:02"))
                    .andExpect(jsonPath("$[0].lastAt").value("2026-10-26T18:33:45"))
                    .andExpect(jsonPath("$[0].syncedAt").isNotEmpty())
                    .andExpect(jsonPath("$[1].file").value("system-2026-10-25.log"));
        }

        @Test
        @DisplayName("a device without log lists nothing")
        void emptyList() throws Exception {
            mockMvc.perform(get(logsPath()).header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));
        }

        @Test
        @DisplayName("returns a file as the player wrote it, across pieces")
        void wholeFile() throws Exception {
            playerAppend(TODAY_FILE, 0, L1).andExpect(status().isOk());
            playerAppend(TODAY_FILE, bytes(L1), L2 + L3).andExpect(status().isOk());

            assertThat(fileText(TODAY_FILE, null)).isEqualTo(L1 + L2 + L3);
        }

        @Test
        @DisplayName("tail returns only the last lines, even when they start in an earlier piece")
        void tail() throws Exception {
            playerAppend(TODAY_FILE, 0, L1 + L2).andExpect(status().isOk());
            playerAppend(TODAY_FILE, bytes(L1 + L2), L3).andExpect(status().isOk());

            assertThat(fileText(TODAY_FILE, 1)).isEqualTo(L3);
            assertThat(fileText(TODAY_FILE, 2)).isEqualTo(L2 + L3);
            assertThat(fileText(TODAY_FILE, 50)).isEqualTo(L1 + L2 + L3);
        }

        @Test
        @DisplayName("download=true sends the file as an attachment named after the device")
        void download() throws Exception {
            playerAppend(TODAY_FILE, 0, L1).andExpect(status().isOk());

            MvcResult started = mockMvc.perform(get(logsPath() + "/" + TODAY_FILE).param("download", "true")
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(request().asyncStarted())
                    .andReturn();
            mockMvc.perform(asyncDispatch(started))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Disposition", containsString("attachment")))
                    .andExpect(header().string("Content-Disposition", containsString(TODAY_FILE)))
                    .andExpect(header().string("Content-Type", containsString("text/plain")));
        }

        @Test
        @DisplayName("an unknown file is 404 and a bad name 400")
        void missingFile() throws Exception {
            mockMvc.perform(get(logsPath() + "/system-2020-01-01.log").header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("devices.log_not_found"));
            mockMvc.perform(get(logsPath() + "/notes.txt").header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("devices.log_file_invalid"));
        }

        @Test
        @DisplayName("the archive zips every file of the period, one entry each")
        void archive() throws Exception {
            playerAppend("system-2026-10-24.log", 0, L1).andExpect(status().isOk());
            playerAppend("system-2026-10-25.log", 0, L2).andExpect(status().isOk());
            playerAppend("system-2026-10-26.log", 0, L3).andExpect(status().isOk());

            MvcResult started = mockMvc.perform(get(logsPath() + "/archive").param("from", "2026-10-25").param("to", "2026-10-26")
                            .header("Authorization", "Bearer " + userToken))
                    .andExpect(request().asyncStarted())
                    .andReturn();
            MvcResult done = mockMvc.perform(asyncDispatch(started))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Disposition", containsString(".zip")))
                    .andReturn();

            Map<String, String> entries = new LinkedHashMap<>();
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(done.getResponse().getContentAsByteArray()))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    entries.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
            assertThat(entries).containsExactly(Map.entry("system-2026-10-25.log", L2), Map.entry("system-2026-10-26.log", L3));
        }

        @Test
        @DisplayName("a reversed or too long period is 400, an empty one 404")
        void archiveRange() throws Exception {
            playerAppend("system-2026-10-26.log", 0, L1).andExpect(status().isOk());

            requestArchive("2026-10-26", "2026-10-25").andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("devices.log_range_invalid"))
                    .andExpect(jsonPath("$.params.maxDays").value(62));
            requestArchive("2026-01-01", "2026-10-26").andExpect(status().isBadRequest());
            requestArchive("2026-09-01", "2026-09-30").andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("devices.log_not_found"));
        }

        @Test
        @DisplayName("outsiders cannot read it and a device of another organization is 404")
        void access() throws Exception {
            mockMvc.perform(get(logsPath()).header("Authorization", "Bearer " + outsiderToken))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/organizations/beta/devices/" + deviceRowId + "/logs").header("Authorization", "Bearer " + outsiderToken))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/organizations/" + acmeSlug + "/devices/999999/logs").header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("retention")
    class Retention {

        @Test
        @DisplayName("the nightly cleanup deletes the pieces older than the retention with their R2 objects")
        void purge() throws Exception {
            String old = "system-" + LocalDate.now().minusDays(200) + ".log";
            playerAppend(old, 0, L1).andExpect(status().isOk());
            playerAppend(TODAY_FILE, 0, L2).andExpect(status().isOk());
            String oldKey = jdbcTemplate.queryForObject("SELECT storage_key FROM device_log_chunks WHERE file_name = ?", String.class, old);

            assertThat(logService.purgeExpired()).isEqualTo(1);

            assertThat(r2.exists(oldKey)).isFalse();
            mockMvc.perform(get("/player/logs/cursor").header("Authorization", "Device " + deviceToken))
                    .andExpect(jsonPath("$.files['" + old + "']").doesNotExist())
                    .andExpect(jsonPath("$.files['" + TODAY_FILE + "']").value(bytes(L2)));
            assertThat(logService.purgeExpired()).isZero();
        }
    }

    // ---------- helpers ----------

    private static long bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    private ResultActions playerAppend(String file, long from, String text) throws Exception {
        return mockMvc.perform(post("/player/logs").param("file", file).param("from", String.valueOf(from))
                .header("Authorization", "Device " + deviceToken)
                .contentType(MediaType.TEXT_PLAIN)
                .content(text.getBytes(StandardCharsets.UTF_8)));
    }

    private ResultActions relayAppend(String token, String uuid, String file, long from, String text) throws Exception {
        return mockMvc.perform(post("/devices/" + uuid + "/logs").param("file", file).param("from", String.valueOf(from))
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.TEXT_PLAIN)
                .content(text.getBytes(StandardCharsets.UTF_8)));
    }

    private ResultActions requestArchive(String from, String to) throws Exception {
        return mockMvc.perform(get(logsPath() + "/archive").param("from", from).param("to", to)
                .header("Authorization", "Bearer " + adminToken));
    }

    private String fileText(String file, Integer tail) throws Exception {
        var req = get(logsPath() + "/" + file).header("Authorization", "Bearer " + adminToken);
        if (tail != null) {
            req = req.param("tail", String.valueOf(tail));
        }
        MvcResult started = mockMvc.perform(req).andExpect(request().asyncStarted()).andReturn();
        MvcResult done = mockMvc.perform(asyncDispatch(started)).andExpect(status().isOk()).andReturn();
        return new String(done.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private int chunkCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM device_log_chunks", Integer.class);
    }

    private String logsPath() {
        return "/organizations/" + acmeSlug + "/devices/" + deviceRowId + "/logs";
    }

    private JsonNode createDevice(String token, String slug, String name) throws Exception {
        MvcResult r = mockMvc.perform(post("/organizations/" + slug + "/devices")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("name", name))))
                .andExpect(status().isCreated())
                .andReturn();
        return om.readTree(r.getResponse().getContentAsString());
    }

    private String createOrg(String token, String name, String slug) throws Exception {
        MvcResult r = mockMvc.perform(post("/organizations")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("name", name, "slug", slug))))
                .andExpect(status().isOk())
                .andReturn();
        return (String) om.readValue(r.getResponse().getContentAsString(), Map.class).get("slug");
    }

    private Integer userIdOf(String email) {
        return jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", Integer.class, email);
    }

    private void addMember(Integer orgId, Integer userId, String role) {
        Long nextId = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(id), 0) + 1 FROM organization_members", Long.class);
        jdbcTemplate.update("INSERT INTO organization_members (id, user_id, organization_id, role, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", nextId, userId, orgId, role);
    }
}
