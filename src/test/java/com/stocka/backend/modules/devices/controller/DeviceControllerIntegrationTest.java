package com.stocka.backend.modules.devices.controller;

import static com.stocka.backend.modules.organizations.IntegrationTestSupport.ADMIN_EMAIL;
import static com.stocka.backend.modules.organizations.IntegrationTestSupport.ADMIN_PASSWORD;
import static com.stocka.backend.modules.organizations.IntegrationTestSupport.buildMockMvc;
import static com.stocka.backend.modules.organizations.IntegrationTestSupport.login;
import static com.stocka.backend.modules.organizations.IntegrationTestSupport.signupAndLogin;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
 * End-to-end coverage for the devices feature (belén players), gated like ports to organizations
 * whose owner is a global admin.
 *
 * <p>Fixture: the seeded global admin ({@link IntegrationTestSupport#ADMIN_EMAIL}) owns "Acme" and
 * "Acme Dos" (admin-owned orgs) and Acme has an extra MANAGER, USER and SPECTATOR member; a regular
 * user owns "Beta" (not admin-owned).
 */
@SpringBootTest
@DisplayName("Device feature (integration)")
class DeviceControllerIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final ObjectMapper om = new ObjectMapper();

    private MockMvc mockMvc;
    private String adminToken;
    private String managerToken;
    private String userToken;
    private String spectatorToken;
    private String outsiderToken;
    private String acmeSlug;
    private String acmeDosSlug;
    private String betaSlug;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = buildMockMvc(context);
        IntegrationTestSupport.cleanDatabase(jdbcTemplate);

        adminToken = login(mockMvc, om, ADMIN_EMAIL, ADMIN_PASSWORD);
        acmeSlug = createOrg(adminToken, "Acme", "acme");
        acmeDosSlug = createOrg(adminToken, "Acme Dos", "acme-dos");

        outsiderToken = signupAndLogin(mockMvc, om, jdbcTemplate, "out@test.com", "outsider");
        betaSlug = createOrg(outsiderToken, "Beta", "beta");

        managerToken = signupAndLogin(mockMvc, om, jdbcTemplate, "manager@test.com", "manager");
        userToken = signupAndLogin(mockMvc, om, jdbcTemplate, "user@test.com", "user");
        spectatorToken = signupAndLogin(mockMvc, om, jdbcTemplate, "spectator@test.com", "spectator");

        Integer acmeId = jdbcTemplate.queryForObject(
                "SELECT id FROM organizations WHERE slug = ?", Integer.class, "acme");
        addMember(acmeId, userIdOf("manager@test.com"), "MANAGER");
        addMember(acmeId, userIdOf("user@test.com"), "USER");
        addMember(acmeId, userIdOf("spectator@test.com"), "SPECTATOR");
    }

    @Nested
    @DisplayName("create and list")
    class CreateAndList {

        @Test
        @DisplayName("admin owner creates a device and receives the token once, with its prefix and the heartbeat URL")
        void adminOwner_can_createDevice() throws Exception {
            MvcResult created = createDevice(adminToken, acmeSlug, "Belén Martorell")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.device.id").isNumber())
                    .andExpect(jsonPath("$.device.name").value("Belén Martorell"))
                    .andExpect(jsonPath("$.device.board").value("rpi4b"))
                    .andExpect(jsonPath("$.device.online").value(false))
                    .andExpect(jsonPath("$.device.deviceId").value(nullValue()))
                    .andExpect(jsonPath("$.device.lastSeenAt").value(nullValue()))
                    .andExpect(jsonPath("$.device.lastState").value(nullValue()))
                    .andExpect(jsonPath("$.device.lastOverrides").isArray())
                    .andExpect(jsonPath("$.device.lastOverrides", hasSize(0)))
                    .andExpect(jsonPath("$.device.createdAt").isNotEmpty())
                    .andExpect(jsonPath("$.device.tokenHash").doesNotExist())
                    .andExpect(jsonPath("$.heartbeatToken").value(startsWith("bht_")))
                    .andExpect(jsonPath("$.heartbeatUrl").value("http://localhost/player/heartbeat"))
                    .andReturn();
            JsonNode body = om.readTree(created.getResponse().getContentAsString());
            String token = body.get("heartbeatToken").asText();
            assertThat(token).hasSize(4 + 43);
            assertThat(body.get("device").get("tokenPrefix").asText()).isEqualTo(token.substring(0, 8));

            String storedHash = jdbcTemplate.queryForObject(
                    "SELECT token_hash FROM devices WHERE id = ?", String.class, body.get("device").get("id").asInt());
            assertThat(storedHash).hasSize(64).isNotEqualTo(token);
            Integer createdBy = jdbcTemplate.queryForObject(
                    "SELECT created_by FROM devices WHERE id = ?", Integer.class, body.get("device").get("id").asInt());
            assertThat(createdBy).isEqualTo(userIdOf(ADMIN_EMAIL));
        }

        @Test
        @DisplayName("the list never includes the token and is ordered by name")
        void list_hidesToken_andOrdersByName() throws Exception {
            createDevice(adminToken, acmeSlug, "Zeta").andExpect(status().isCreated());
            createDevice(adminToken, acmeSlug, "Alfa").andExpect(status().isCreated());

            mockMvc.perform(get(devicesPath(acmeSlug)).header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(2)))
                    .andExpect(jsonPath("$[0].name").value("Alfa"))
                    .andExpect(jsonPath("$[1].name").value("Zeta"))
                    .andExpect(jsonPath("$[0].tokenPrefix").value(startsWith("bht_")))
                    .andExpect(jsonPath("$[0].heartbeatToken").doesNotExist())
                    .andExpect(jsonPath("$[0].tokenHash").doesNotExist())
                    .andExpect(jsonPath("$[0].online").value(false));
        }

        @Test
        @DisplayName("the name is trimmed before being stored")
        void create_trimsName() throws Exception {
            createDevice(adminToken, acmeSlug, "   Pessebre   ")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.device.name").value("Pessebre"));
        }

        @Test
        @DisplayName("the same name can be used in two different organizations")
        void sameName_inAnotherOrg_isAllowed() throws Exception {
            createDevice(adminToken, acmeSlug, "Belén").andExpect(status().isCreated());
            createDevice(adminToken, acmeDosSlug, "Belén").andExpect(status().isCreated());
            mockMvc.perform(get(devicesPath(acmeDosSlug)).header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)));
        }
    }

    @Nested
    @DisplayName("roles and gating")
    class Roles {

        @Test
        @DisplayName("manager of an admin-owned org can create devices")
        void manager_can_create() throws Exception {
            createDevice(managerToken, acmeSlug, "Belén").andExpect(status().isCreated());
        }

        @Test
        @DisplayName("spectator can list but cannot create, rename, delete or rotate")
        void spectator_canRead_butCannotManage() throws Exception {
            Integer id = createAndGetId(adminToken, acmeSlug, "Belén");

            mockMvc.perform(get(devicesPath(acmeSlug)).header("Authorization", "Bearer " + spectatorToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)));
            createDevice(spectatorToken, acmeSlug, "Altre").andExpect(status().isForbidden());
            renameDevice(spectatorToken, acmeSlug, id, "Altre").andExpect(status().isForbidden());
            mockMvc.perform(delete(devicesPath(acmeSlug) + "/" + id).header("Authorization", "Bearer " + spectatorToken))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post(devicesPath(acmeSlug) + "/" + id + "/rotate-token")
                            .header("Authorization", "Bearer " + spectatorToken))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("a plain USER member can list but cannot create")
        void user_canRead_butCannotManage() throws Exception {
            mockMvc.perform(get(devicesPath(acmeSlug)).header("Authorization", "Bearer " + userToken))
                    .andExpect(status().isOk());
            createDevice(userToken, acmeSlug, "Belén").andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("a non-member of an admin-owned org is forbidden")
        void nonMember_isForbidden() throws Exception {
            mockMvc.perform(get(devicesPath(acmeSlug)).header("Authorization", "Bearer " + outsiderToken))
                    .andExpect(status().isForbidden());
            createDevice(outsiderToken, acmeSlug, "Belén").andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("a non-admin-owned org cannot use the feature, even for its owner")
        void nonAdminOwnedOrg_isForbidden() throws Exception {
            createDevice(outsiderToken, betaSlug, "Belén").andExpect(status().isForbidden());
            mockMvc.perform(get(devicesPath(betaSlug)).header("Authorization", "Bearer " + outsiderToken))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("an unauthenticated request is rejected with 401")
        void anonymous_isUnauthorized() throws Exception {
            mockMvc.perform(get(devicesPath(acmeSlug))).andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("validation and conflicts")
    class Validation {

        @Test
        @DisplayName("a blank name returns 400 validation.failed naming the field")
        void blankName_returns400() throws Exception {
            createDevice(adminToken, acmeSlug, "   ")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("validation.failed"))
                    .andExpect(jsonPath("$.errors[0].field").value("name"));
        }

        @Test
        @DisplayName("a missing name returns 400 validation.failed")
        void missingName_returns400() throws Exception {
            mockMvc.perform(post(devicesPath(acmeSlug))
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("validation.failed"));
        }

        @Test
        @DisplayName("a name longer than 80 characters returns 400")
        void longName_returns400() throws Exception {
            createDevice(adminToken, acmeSlug, "x".repeat(81))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("validation.failed"));
            createDevice(adminToken, acmeSlug, "y".repeat(80)).andExpect(status().isCreated());
        }

        @Test
        @DisplayName("a malformed body returns 400 request.malformed_body")
        void malformedBody_returns400() throws Exception {
            mockMvc.perform(post(devicesPath(acmeSlug))
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\": "))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("request.malformed_body"));
        }

        @Test
        @DisplayName("a duplicate name in the same org returns 409 devices.name_taken")
        void duplicateName_returns409() throws Exception {
            createDevice(adminToken, acmeSlug, "Belén").andExpect(status().isCreated());
            createDevice(adminToken, acmeSlug, " Belén ")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("devices.name_taken"));
        }

        @Test
        @DisplayName("an unknown organization slug returns 404")
        void unknownOrg_returns404() throws Exception {
            // The admin bypasses the membership gate, so the slug lookup in the body is what fails.
            createDevice(adminToken, "nope", "Belén").andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("rename")
    class Rename {

        @Test
        @DisplayName("renames and returns the device view")
        void rename_returnsUpdatedDevice() throws Exception {
            Integer id = createAndGetId(adminToken, acmeSlug, "Vell");

            renameDevice(managerToken, acmeSlug, id, "  Nou  ")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(id))
                    .andExpect(jsonPath("$.name").value("Nou"))
                    .andExpect(jsonPath("$.heartbeatToken").doesNotExist());

            mockMvc.perform(get(devicesPath(acmeSlug)).header("Authorization", "Bearer " + adminToken))
                    .andExpect(jsonPath("$[0].name").value("Nou"));
        }

        @Test
        @DisplayName("renaming to another device's name returns 409")
        void rename_toTakenName_returns409() throws Exception {
            createAndGetId(adminToken, acmeSlug, "A");
            Integer b = createAndGetId(adminToken, acmeSlug, "B");
            renameDevice(adminToken, acmeSlug, b, "A")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("devices.name_taken"));
        }

        @Test
        @DisplayName("renaming to the same name is a no-op 200")
        void rename_toSameName_isOk() throws Exception {
            Integer id = createAndGetId(adminToken, acmeSlug, "A");
            renameDevice(adminToken, acmeSlug, id, "A").andExpect(status().isOk());
        }

        @Test
        @DisplayName("a blank new name returns 400")
        void rename_blank_returns400() throws Exception {
            Integer id = createAndGetId(adminToken, acmeSlug, "A");
            renameDevice(adminToken, acmeSlug, id, "")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("validation.failed"));
        }

        @Test
        @DisplayName("an unknown id or an id of another org returns 404 devices.not_found")
        void rename_unknownOrForeign_returns404() throws Exception {
            Integer foreign = createAndGetId(adminToken, acmeDosSlug, "A");
            renameDevice(adminToken, acmeSlug, 999999, "B")
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("devices.not_found"));
            renameDevice(adminToken, acmeSlug, foreign, "B")
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("devices.not_found"));
        }
    }

    @Nested
    @DisplayName("local address and PIN")
    class LocalConnection {

        @Test
        @DisplayName("create stores the normalized address and the PIN and shows both to the manager")
        void create_withAddressAndPin() throws Exception {
            createDevice(managerToken, acmeSlug, Map.of("name", "Belén", "localUrl", "belen.local", "pin", "123456"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.device.localUrl").value("http://belen.local:8080"))
                    .andExpect(jsonPath("$.device.pin").value("123456"))
                    .andExpect(jsonPath("$.device.hasPin").value(true));
            assertThat(jdbcTemplate.queryForObject("SELECT pin FROM devices WHERE name = 'Belén'", String.class)).isEqualTo("123456");
        }

        @Test
        @DisplayName("a device without address nor PIN lists them as null and hasPin false")
        void create_withoutConnection() throws Exception {
            createDevice(adminToken, acmeSlug, "Belén").andExpect(status().isCreated())
                    .andExpect(jsonPath("$.device.localUrl").value(nullValue()))
                    .andExpect(jsonPath("$.device.pin").value(nullValue()))
                    .andExpect(jsonPath("$.device.hasPin").value(false));
        }

        @Test
        @DisplayName("OWNER, MANAGER and USER see the PIN in the list; SPECTATOR only the address and hasPin")
        void list_revealsPinToWhoMaySendShows() throws Exception {
            createDevice(adminToken, acmeSlug, Map.of("name", "Belén", "localUrl", "http://10.42.0.1:8080", "pin", "123456"))
                    .andExpect(status().isCreated());

            for (String token : new String[] {adminToken, managerToken, userToken}) {
                mockMvc.perform(get(devicesPath(acmeSlug)).header("Authorization", "Bearer " + token))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$[0].localUrl").value("http://10.42.0.1:8080"))
                        .andExpect(jsonPath("$[0].pin").value("123456"));
            }
            mockMvc.perform(get(devicesPath(acmeSlug)).header("Authorization", "Bearer " + spectatorToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].localUrl").value("http://10.42.0.1:8080"))
                    .andExpect(jsonPath("$[0].pin").value(nullValue()))
                    .andExpect(jsonPath("$[0].hasPin").value(true));
        }

        @Test
        @DisplayName("an invalid address returns 400 devices.local_url_invalid and creates nothing")
        void create_invalidAddress_returns400() throws Exception {
            createDevice(adminToken, acmeSlug, Map.of("name", "Belén", "localUrl", "ftp://belen.local"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("devices.local_url_invalid"));
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM devices", Integer.class)).isZero();
        }

        @Test
        @DisplayName("a PIN that is not 6 digits returns 400 devices.pin_invalid")
        void create_invalidPin_returns400() throws Exception {
            createDevice(adminToken, acmeSlug, Map.of("name", "Belén", "pin", "12ab56"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("devices.pin_invalid"));
        }

        @Test
        @DisplayName("an address longer than 255 characters fails validation")
        void create_tooLongAddress_returns400() throws Exception {
            createDevice(adminToken, acmeSlug, Map.of("name", "Belén", "localUrl", "x".repeat(256)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("validation.failed"));
        }

        @Test
        @DisplayName("PATCH changes only the fields it carries and an empty string removes the address or the PIN")
        void update_partialAndClear() throws Exception {
            Integer id = createAndGetId(adminToken, acmeSlug, Map.of("name", "Belén", "localUrl", "belen.local", "pin", "123456"));

            patchDevice(managerToken, acmeSlug, id, Map.of("localUrl", "10.42.0.1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("Belén"))
                    .andExpect(jsonPath("$.localUrl").value("http://10.42.0.1:8080"))
                    .andExpect(jsonPath("$.pin").value("123456"));

            patchDevice(managerToken, acmeSlug, id, Map.of("pin", ""))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.localUrl").value("http://10.42.0.1:8080"))
                    .andExpect(jsonPath("$.pin").value(nullValue()))
                    .andExpect(jsonPath("$.hasPin").value(false));

            patchDevice(managerToken, acmeSlug, id, Map.of("localUrl", ""))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.localUrl").value(nullValue()));
        }

        @Test
        @DisplayName("PATCH with an empty body is a no-op 200")
        void update_emptyBody_isOk() throws Exception {
            Integer id = createAndGetId(adminToken, acmeSlug, "Belén");
            patchDevice(adminToken, acmeSlug, id, Map.of())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("Belén"));
        }

        @Test
        @DisplayName("a USER member cannot PATCH the device itself")
        void update_byUser_isForbidden() throws Exception {
            Integer id = createAndGetId(adminToken, acmeSlug, "Belén");
            patchDevice(userToken, acmeSlug, id, Map.of("localUrl", "belen.local")).andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("a USER saves from the editor the address, the PIN and the uuid the player answered with")
        void connection_byUser_savesAndBinds() throws Exception {
            Integer id = createAndGetId(adminToken, acmeSlug, "Belén");

            patchConnection(userToken, acmeSlug, id, Map.of("localUrl", "http://belen.local:8080", "pin", "654321", "deviceId", "uuid-1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("Belén"))
                    .andExpect(jsonPath("$.localUrl").value("http://belen.local:8080"))
                    .andExpect(jsonPath("$.pin").value("654321"))
                    .andExpect(jsonPath("$.deviceId").value("uuid-1"));

            patchConnection(managerToken, acmeSlug, id, Map.of("localUrl", "10.42.0.1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.localUrl").value("http://10.42.0.1:8080"))
                    .andExpect(jsonPath("$.pin").value("654321"))
                    .andExpect(jsonPath("$.deviceId").value("uuid-1"));
        }

        @Test
        @DisplayName("the connection endpoint ignores a name in the body")
        void connection_ignoresName() throws Exception {
            Integer id = createAndGetId(adminToken, acmeSlug, "Belén");
            patchConnection(userToken, acmeSlug, id, Map.of("name", "Un altre", "pin", "111111"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("Belén"));
        }

        @Test
        @DisplayName("SPECTATOR, outsiders and non-admin-owned orgs cannot save the connection")
        void connection_forbidden() throws Exception {
            Integer id = createAndGetId(adminToken, acmeSlug, "Belén");
            patchConnection(spectatorToken, acmeSlug, id, Map.of("pin", "111111")).andExpect(status().isForbidden());
            patchConnection(outsiderToken, acmeSlug, id, Map.of("pin", "111111")).andExpect(status().isForbidden());
            patchConnection(outsiderToken, betaSlug, id, Map.of("pin", "111111")).andExpect(status().isForbidden());
            mockMvc.perform(patch(devicesPath(acmeSlug) + "/" + id + "/connection")
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("the connection endpoint validates like the others and 404s for foreign ids")
        void connection_validationAndNotFound() throws Exception {
            Integer id = createAndGetId(adminToken, acmeSlug, "Belén");
            Integer foreign = createAndGetId(adminToken, acmeDosSlug, "Belén");

            patchConnection(userToken, acmeSlug, id, Map.of("pin", "1"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("devices.pin_invalid"));
            patchConnection(userToken, acmeSlug, id, Map.of("localUrl", "belen local"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("devices.local_url_invalid"));
            patchConnection(userToken, acmeSlug, id, Map.of("deviceId", "u".repeat(37)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("validation.failed"));
            patchConnection(adminToken, acmeSlug, foreign, Map.of("pin", "111111"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("devices.not_found"));
        }
    }

    @Nested
    @DisplayName("delete")
    class Delete {

        @Test
        @DisplayName("soft-deletes, hides the row and frees the name")
        void delete_softDeletesAndFreesName() throws Exception {
            Integer id = createAndGetId(adminToken, acmeSlug, "Belén");

            mockMvc.perform(delete(devicesPath(acmeSlug) + "/" + id).header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isNoContent());

            mockMvc.perform(get(devicesPath(acmeSlug)).header("Authorization", "Bearer " + adminToken))
                    .andExpect(jsonPath("$", hasSize(0)));
            String mangled = jdbcTemplate.queryForObject("SELECT name FROM devices WHERE id = ?", String.class, id);
            assertThat(mangled).isEqualTo("Belén::deleted::" + id);
            assertThat(jdbcTemplate.queryForObject("SELECT deleted_at FROM devices WHERE id = ?", Object.class, id))
                    .isNotNull();

            createDevice(adminToken, acmeSlug, "Belén").andExpect(status().isCreated());
        }

        @Test
        @DisplayName("deleting twice, an unknown id or a foreign id returns 404")
        void delete_unknown_returns404() throws Exception {
            Integer id = createAndGetId(adminToken, acmeSlug, "Belén");
            Integer foreign = createAndGetId(adminToken, acmeDosSlug, "Belén");
            mockMvc.perform(delete(devicesPath(acmeSlug) + "/" + id).header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isNoContent());
            mockMvc.perform(delete(devicesPath(acmeSlug) + "/" + id).header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("devices.not_found"));
            mockMvc.perform(delete(devicesPath(acmeSlug) + "/" + foreign).header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("rotate token")
    class Rotate {

        @Test
        @DisplayName("issues a new token, updates the prefix and the stored hash")
        void rotate_issuesNewToken() throws Exception {
            MvcResult created = createDevice(adminToken, acmeSlug, "Belén").andExpect(status().isCreated()).andReturn();
            JsonNode body = om.readTree(created.getResponse().getContentAsString());
            Integer id = body.get("device").get("id").asInt();
            String oldToken = body.get("heartbeatToken").asText();
            String oldHash = jdbcTemplate.queryForObject("SELECT token_hash FROM devices WHERE id = ?", String.class, id);

            MvcResult rotated = mockMvc.perform(post(devicesPath(acmeSlug) + "/" + id + "/rotate-token")
                            .header("Authorization", "Bearer " + managerToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.device.id").value(id))
                    .andExpect(jsonPath("$.heartbeatToken").value(startsWith("bht_")))
                    .andExpect(jsonPath("$.heartbeatUrl").value("http://localhost/player/heartbeat"))
                    .andReturn();
            JsonNode rotatedBody = om.readTree(rotated.getResponse().getContentAsString());
            String newToken = rotatedBody.get("heartbeatToken").asText();

            assertThat(newToken).isNotEqualTo(oldToken);
            assertThat(rotatedBody.get("device").get("tokenPrefix").asText()).isEqualTo(newToken.substring(0, 8));
            String newHash = jdbcTemplate.queryForObject("SELECT token_hash FROM devices WHERE id = ?", String.class, id);
            assertThat(newHash).isNotEqualTo(oldHash);
            mockMvc.perform(get(devicesPath(acmeSlug)).header("Authorization", "Bearer " + adminToken))
                    .andExpect(jsonPath("$[0].tokenPrefix").value(newToken.substring(0, 8)));
        }

        @Test
        @DisplayName("rotating an unknown or foreign device returns 404")
        void rotate_unknown_returns404() throws Exception {
            Integer foreign = createAndGetId(adminToken, acmeDosSlug, "Belén");
            mockMvc.perform(post(devicesPath(acmeSlug) + "/999999/rotate-token").header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("devices.not_found"));
            mockMvc.perform(post(devicesPath(acmeSlug) + "/" + foreign + "/rotate-token")
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("organization cascade")
    class Cascade {

        @Test
        @DisplayName("deleting the organization soft-deletes its devices")
        void deletingOrg_softDeletesDevices() throws Exception {
            Integer id = createAndGetId(adminToken, acmeDosSlug, "Belén");

            mockMvc.perform(delete("/organizations/" + acmeDosSlug).header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().is2xxSuccessful());

            assertThat(jdbcTemplate.queryForObject("SELECT deleted_at FROM devices WHERE id = ?", Object.class, id))
                    .isNotNull();
        }
    }

    // ---------- helpers ----------

    private String devicesPath(String slug) {
        return "/organizations/" + slug + "/devices";
    }

    private ResultActions createDevice(String token, String slug, String name) throws Exception {
        return createDevice(token, slug, Map.of("name", name));
    }

    private ResultActions createDevice(String token, String slug, Map<String, String> body) throws Exception {
        return mockMvc.perform(post(devicesPath(slug))
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(body)));
    }

    private Integer createAndGetId(String token, String slug, String name) throws Exception {
        return createAndGetId(token, slug, Map.of("name", name));
    }

    private Integer createAndGetId(String token, String slug, Map<String, String> body) throws Exception {
        MvcResult r = createDevice(token, slug, body).andExpect(status().isCreated()).andReturn();
        return om.readTree(r.getResponse().getContentAsString()).get("device").get("id").asInt();
    }

    private ResultActions patchDevice(String token, String slug, Integer id, Map<String, String> body) throws Exception {
        return mockMvc.perform(patch(devicesPath(slug) + "/" + id)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(body)));
    }

    private ResultActions patchConnection(String token, String slug, Integer id, Map<String, String> body) throws Exception {
        return mockMvc.perform(patch(devicesPath(slug) + "/" + id + "/connection")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(body)));
    }

    private ResultActions renameDevice(String token, String slug, Integer id, String name) throws Exception {
        return mockMvc.perform(patch(devicesPath(slug) + "/" + id)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("name", name))));
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
        Long nextId = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(id), 0) + 1 FROM organization_members", Long.class);
        jdbcTemplate.update(
                "INSERT INTO organization_members (id, user_id, organization_id, role, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                nextId, userId, orgId, role);
    }
}
