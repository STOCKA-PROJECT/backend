package com.stocka.backend.modules.devices.controller;

import static com.stocka.backend.modules.organizations.IntegrationTestSupport.ADMIN_EMAIL;
import static com.stocka.backend.modules.organizations.IntegrationTestSupport.ADMIN_PASSWORD;
import static com.stocka.backend.modules.organizations.IntegrationTestSupport.buildMockMvc;
import static com.stocka.backend.modules.organizations.IntegrationTestSupport.login;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
import com.stocka.backend.modules.organizations.IntegrationTestSupport;

/**
 * End-to-end coverage of the public heartbeat endpoint: a registered player posts its state with
 * {@code Authorization: Device <token>} and the organization sees it in the device list.
 *
 * <p>Fixture: the seeded global admin owns "Acme" with one registered device whose raw token is kept
 * from the creation response. Rate limiting is disabled by the test properties; the mapping of
 * {@code POST /player/heartbeat} to its policy is covered by {@code RateLimitFilterTest}.
 */
@SpringBootTest
@DisplayName("Player heartbeat (integration)")
class HeartbeatControllerIntegrationTest {

    private static final String HEARTBEAT = "/player/heartbeat";

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final ObjectMapper om = new ObjectMapper();

    private MockMvc mockMvc;
    private String adminToken;
    private String acmeSlug;
    private Integer deviceRowId;
    private String deviceToken;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = buildMockMvc(context);
        IntegrationTestSupport.cleanDatabase(jdbcTemplate);

        adminToken = login(mockMvc, om, ADMIN_EMAIL, ADMIN_PASSWORD);
        acmeSlug = createOrg(adminToken, "Acme", "acme");
        JsonNode created = createDevice(adminToken, acmeSlug, "Belén Martorell");
        deviceRowId = created.get("device").get("id").asInt();
        deviceToken = created.get("heartbeatToken").asText();
    }

    @Nested
    @DisplayName("valid heartbeat")
    class Valid {

        @Test
        @DisplayName("returns 204 and the device list reflects the report as online")
        void heartbeat_updatesDevice() throws Exception {
            Map<String, Object> body = fullBody("0f1e2d3c-0000-4000-8000-000000000001", "PLAYING");

            heartbeat("Device " + deviceToken, body).andExpect(status().isNoContent());

            mockMvc.perform(get(devicesPath()).header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].id").value(deviceRowId))
                    .andExpect(jsonPath("$[0].online").value(true))
                    .andExpect(jsonPath("$[0].deviceId").value("0f1e2d3c-0000-4000-8000-000000000001"))
                    .andExpect(jsonPath("$[0].lastState").value("PLAYING"))
                    .andExpect(jsonPath("$[0].lastShowHash").value("sha256:6e46887d"))
                    .andExpect(jsonPath("$[0].lastShowName").value("Nadal 2026"))
                    .andExpect(jsonPath("$[0].lastOverrides", hasSize(2)))
                    .andExpect(jsonPath("$[0].lastOverrides", hasItem("Angel")))
                    .andExpect(jsonPath("$[0].lastAlerts", hasSize(1)))
                    .andExpect(jsonPath("$[0].lastAlerts[0].code").value("motor_timeout"))
                    .andExpect(jsonPath("$[0].lastAlerts[0].port").value("Angel"))
                    .andExpect(jsonPath("$[0].lastVersion").value("2.0.1"))
                    .andExpect(jsonPath("$[0].lastIp").value("127.0.0.1"))
                    .andExpect(jsonPath("$[0].lastError").value("relay 3 timeout"))
                    .andExpect(jsonPath("$[0].lastSeenAt").isNotEmpty())
                    .andExpect(jsonPath("$[0].name").value("Belén Martorell"));

            Long uptime = jdbcTemplate.queryForObject("SELECT last_uptime_s FROM devices WHERE id = ?", Long.class, deviceRowId);
            String netMode = jdbcTemplate.queryForObject("SELECT last_net_mode FROM devices WHERE id = ?", String.class, deviceRowId);
            org.assertj.core.api.Assertions.assertThat(uptime).isEqualTo(86400L);
            org.assertj.core.api.Assertions.assertThat(netMode).isEqualTo("ap");
        }

        @Test
        @DisplayName("a minimal body (deviceId + state) is enough and clears the optional fields")
        void heartbeat_minimalBody() throws Exception {
            heartbeat("Device " + deviceToken, fullBody("uuid-1", "PLAYING")).andExpect(status().isNoContent());
            heartbeat("Device " + deviceToken, Map.of("deviceId", "uuid-1", "state", "IDLE")).andExpect(status().isNoContent());

            mockMvc.perform(get(devicesPath()).header("Authorization", "Bearer " + adminToken))
                    .andExpect(jsonPath("$[0].lastState").value("IDLE"))
                    .andExpect(jsonPath("$[0].lastShowHash").value(nullValue()))
                    .andExpect(jsonPath("$[0].lastShowName").value(nullValue()))
                    .andExpect(jsonPath("$[0].lastOverrides", hasSize(0)))
                    .andExpect(jsonPath("$[0].lastAlerts", hasSize(0)))
                    .andExpect(jsonPath("$[0].lastError").value(nullValue()))
                    .andExpect(jsonPath("$[0].online").value(true));
        }

        @Test
        @DisplayName("a different deviceId later is accepted and overwrites the bound one")
        void heartbeat_differentUuid_isAccepted() throws Exception {
            heartbeat("Device " + deviceToken, Map.of("deviceId", "uuid-1", "state", "IDLE")).andExpect(status().isNoContent());
            heartbeat("Device " + deviceToken, Map.of("deviceId", "uuid-2", "state", "IDLE")).andExpect(status().isNoContent());

            mockMvc.perform(get(devicesPath()).header("Authorization", "Bearer " + adminToken))
                    .andExpect(jsonPath("$[0].deviceId").value("uuid-2"));
        }

        @Test
        @DisplayName("unknown JSON properties are ignored; the lowercase scheme is accepted")
        void heartbeat_ignoresUnknownProperties() throws Exception {
            Map<String, Object> body = new HashMap<>(Map.of("deviceId", "uuid-1", "state", "BOOT"));
            body.put("temperatureC", 41.5);
            body.put("ssid", "belen");
            body.put("ip", "192.168.4.1");
            body.put("name", "belen-pi");
            heartbeat("device " + deviceToken, body).andExpect(status().isNoContent());

            mockMvc.perform(get(devicesPath()).header("Authorization", "Bearer " + adminToken))
                    .andExpect(jsonPath("$[0].name").value("Belén Martorell"))
                    .andExpect(jsonPath("$[0].lastIp").value("127.0.0.1"));
        }

        @Test
        @DisplayName("the device goes offline once the last heartbeat is older than the window")
        void heartbeat_staleReport_isOffline() throws Exception {
            heartbeat("Device " + deviceToken, Map.of("deviceId", "uuid-1", "state", "IDLE")).andExpect(status().isNoContent());
            jdbcTemplate.update("UPDATE devices SET last_seen_at = DATEADD('MINUTE', -10, CURRENT_TIMESTAMP) WHERE id = ?",
                    deviceRowId);

            mockMvc.perform(get(devicesPath()).header("Authorization", "Bearer " + adminToken))
                    .andExpect(jsonPath("$[0].online").value(false))
                    .andExpect(jsonPath("$[0].lastState").value("IDLE"));
        }

        @Test
        @DisplayName("every documented state is accepted")
        void heartbeat_acceptsAllStates() throws Exception {
            for (String state : List.of("BOOT", "SAFE", "IDLE", "PLAYING", "TEST", "ERROR")) {
                heartbeat("Device " + deviceToken, Map.of("deviceId", "uuid-1", "state", state))
                        .andExpect(status().isNoContent());
            }
        }
    }

    @Nested
    @DisplayName("authentication")
    class Authentication {

        @Test
        @DisplayName("an unknown token returns 401 devices.token_invalid and stores nothing")
        void unknownToken_returns401() throws Exception {
            heartbeat("Device bht_" + "A".repeat(43), Map.of("deviceId", "uuid-1", "state", "IDLE"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("devices.token_invalid"));
            mockMvc.perform(get(devicesPath()).header("Authorization", "Bearer " + adminToken))
                    .andExpect(jsonPath("$[0].lastSeenAt").value(nullValue()));
        }

        @Test
        @DisplayName("a missing or malformed Authorization header returns 401 devices.token_invalid")
        void missingOrMalformedHeader_returns401() throws Exception {
            Map<String, Object> body = Map.of("deviceId", "uuid-1", "state", "IDLE");
            heartbeat(null, body)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("devices.token_invalid"));
            heartbeat("Device", body).andExpect(status().isUnauthorized());
            heartbeat("Device ", body).andExpect(status().isUnauthorized());
            heartbeat(deviceToken, body).andExpect(status().isUnauthorized());
            heartbeat("Basic " + deviceToken, body).andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("a device token sent as Bearer is not accepted (and the JWT filter does not choke on it)")
        void bearerDeviceToken_isNotAccepted() throws Exception {
            heartbeat("Bearer " + deviceToken, Map.of("deviceId", "uuid-1", "state", "IDLE"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("devices.token_invalid"));
        }

        @Test
        @DisplayName("a user's SPA JWT is not a device token")
        void spaJwt_isNotAccepted() throws Exception {
            heartbeat("Bearer " + adminToken, Map.of("deviceId", "uuid-1", "state", "IDLE"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("devices.token_invalid"));
            heartbeat("Device " + adminToken, Map.of("deviceId", "uuid-1", "state", "IDLE"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("devices.token_invalid"));
        }

        @Test
        @DisplayName("a device token grants nothing on the REST API")
        void deviceToken_isNotAnApiCredential() throws Exception {
            mockMvc.perform(get("/users/me").header("Authorization", "Device " + deviceToken))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("auth.unauthenticated"));
            mockMvc.perform(get(devicesPath()).header("Authorization", "Device " + deviceToken))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("rotating the token invalidates the previous one immediately")
        void rotatedToken_invalidatesOld() throws Exception {
            MvcResult rotated = mockMvc.perform(post(devicesPath() + "/" + deviceRowId + "/rotate-token")
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andReturn();
            String newToken = om.readTree(rotated.getResponse().getContentAsString()).get("heartbeatToken").asText();

            heartbeat("Device " + deviceToken, Map.of("deviceId", "uuid-1", "state", "IDLE"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("devices.token_invalid"));
            heartbeat("Device " + newToken, Map.of("deviceId", "uuid-1", "state", "IDLE"))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("a deleted device's token stops working")
        void deletedDevice_tokenIsInvalid() throws Exception {
            mockMvc.perform(delete(devicesPath() + "/" + deviceRowId).header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isNoContent());

            heartbeat("Device " + deviceToken, Map.of("deviceId", "uuid-1", "state", "IDLE"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("devices.token_invalid"));
        }
    }

    @Nested
    @DisplayName("body validation")
    class Validation {

        @Test
        @DisplayName("a missing deviceId returns 400 validation.failed naming the field")
        void missingDeviceId_returns400() throws Exception {
            heartbeat("Device " + deviceToken, Map.of("state", "IDLE"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("validation.failed"))
                    .andExpect(jsonPath("$.errors[0].field").value("deviceId"));
        }

        @Test
        @DisplayName("a deviceId longer than 36 characters returns 400")
        void longDeviceId_returns400() throws Exception {
            heartbeat("Device " + deviceToken, Map.of("deviceId", "x".repeat(37), "state", "IDLE"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("deviceId"));
        }

        @Test
        @DisplayName("an unknown or missing state returns 400")
        void badState_returns400() throws Exception {
            heartbeat("Device " + deviceToken, Map.of("deviceId", "uuid-1", "state", "DANCING"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("validation.failed"))
                    .andExpect(jsonPath("$.errors[0].field").value("state"));
            heartbeat("Device " + deviceToken, Map.of("deviceId", "uuid-1", "state", "playing"))
                    .andExpect(status().isBadRequest());
            heartbeat("Device " + deviceToken, Map.of("deviceId", "uuid-1"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("more than 200 overrides or an override longer than 160 characters returns 400")
        void overridesLimits_return400() throws Exception {
            List<String> tooMany = new ArrayList<>();
            for (int i = 0; i < 201; i++) {
                tooMany.add("P" + i);
            }
            heartbeat("Device " + deviceToken, Map.of("deviceId", "uuid-1", "state", "IDLE", "overrides", tooMany))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("validation.failed"));
            heartbeat("Device " + deviceToken, Map.of("deviceId", "uuid-1", "state", "IDLE", "overrides", List.of("z".repeat(161))))
                    .andExpect(status().isBadRequest());
            List<String> exactly200 = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                exactly200.add("P" + i);
            }
            heartbeat("Device " + deviceToken, Map.of("deviceId", "uuid-1", "state", "IDLE", "overrides", exactly200))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("more than 20 alerts or an alert with an over-long field returns 400; 20 are accepted")
        void alertsLimits_return400() throws Exception {
            List<Map<String, Object>> tooMany = new ArrayList<>();
            for (int i = 0; i < 21; i++) {
                tooMany.add(Map.of("code", "motor_timeout", "port", "M" + i));
            }
            heartbeat("Device " + deviceToken, Map.of("deviceId", "uuid-1", "state", "IDLE", "alerts", tooMany))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("validation.failed"));
            heartbeat("Device " + deviceToken, Map.of("deviceId", "uuid-1", "state", "IDLE",
                            "alerts", List.of(Map.of("code", "c".repeat(41)))))
                    .andExpect(status().isBadRequest());
            heartbeat("Device " + deviceToken, Map.of("deviceId", "uuid-1", "state", "IDLE",
                            "alerts", List.of(Map.of("code", "x", "message", "m".repeat(501)))))
                    .andExpect(status().isBadRequest());
            heartbeat("Device " + deviceToken, Map.of("deviceId", "uuid-1", "state", "IDLE", "alerts", tooMany.subList(0, 20)))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("over-long optional strings and a negative uptime return 400")
        void otherLimits_return400() throws Exception {
            heartbeat("Device " + deviceToken, Map.of("deviceId", "uuid-1", "state", "IDLE", "showHash", "h".repeat(81)))
                    .andExpect(status().isBadRequest());
            heartbeat("Device " + deviceToken, Map.of("deviceId", "uuid-1", "state", "IDLE", "showName", "n".repeat(161)))
                    .andExpect(status().isBadRequest());
            heartbeat("Device " + deviceToken, Map.of("deviceId", "uuid-1", "state", "IDLE", "version", "v".repeat(41)))
                    .andExpect(status().isBadRequest());
            heartbeat("Device " + deviceToken, Map.of("deviceId", "uuid-1", "state", "IDLE", "netMode", "n".repeat(9)))
                    .andExpect(status().isBadRequest());
            heartbeat("Device " + deviceToken, Map.of("deviceId", "uuid-1", "state", "IDLE", "uptimeS", -1))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("a malformed JSON body returns 400 request.malformed_body")
        void malformedJson_returns400() throws Exception {
            mockMvc.perform(post(HEARTBEAT)
                            .header("Authorization", "Device " + deviceToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"deviceId\": "))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("request.malformed_body"));
        }
    }

    // ---------- helpers ----------

    private static Map<String, Object> fullBody(String deviceId, String state) {
        Map<String, Object> body = new HashMap<>();
        body.put("deviceId", deviceId);
        body.put("name", "belen-pi");
        body.put("state", state);
        body.put("showHash", "sha256:6e46887d");
        body.put("showName", "Nadal 2026");
        body.put("overrides", List.of("Angel", "Riu"));
        body.put("version", "2.0.1");
        body.put("uptimeS", 86400);
        body.put("netMode", "ap");
        body.put("ssid", "belen");
        body.put("ip", "192.168.4.1");
        body.put("alerts", List.of(Map.of("code", "motor_timeout", "port", "Angel", "message", "Temps màxim", "at", "2026-12-24T18:00:00Z")));
        body.put("error", "relay 3 timeout");
        return body;
    }

    private ResultActions heartbeat(String authorization, Map<String, Object> body) throws Exception {
        var request = post(HEARTBEAT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(body));
        if (authorization != null) {
            request = request.header("Authorization", authorization);
        }
        return mockMvc.perform(request);
    }

    private String devicesPath() {
        return "/organizations/" + acmeSlug + "/devices";
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
}
