package com.stocka.backend.modules.ports.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static com.stocka.backend.modules.organizations.IntegrationTestSupport.ADMIN_EMAIL;
import static com.stocka.backend.modules.organizations.IntegrationTestSupport.ADMIN_PASSWORD;
import static com.stocka.backend.modules.organizations.IntegrationTestSupport.buildMockMvc;
import static com.stocka.backend.modules.organizations.IntegrationTestSupport.login;
import static com.stocka.backend.modules.organizations.IntegrationTestSupport.signupAndLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.context.WebApplicationContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocka.backend.modules.organizations.IntegrationTestSupport;

/**
 * End-to-end coverage for the private ports feature, which is only available on organizations whose
 * owner is a global admin. Each port is related to an existing piece type of the organization.
 *
 * <p>Fixture: the seeded global admin ({@link IntegrationTestSupport#ADMIN_EMAIL}) owns "Acme" (an
 * admin-owned org) with a piece type and an extra MANAGER, USER and SPECTATOR member; a regular user
 * owns "Beta" (not admin-owned).
 */
@SpringBootTest
@DisplayName("Port feature (integration)")
class PortControllerIntegrationTest {

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
    private Integer acmePieceTypeId;
    private Integer acmeSegmentTypeId;
    private String betaSlug;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = buildMockMvc(context);
        IntegrationTestSupport.cleanDatabase(jdbcTemplate);

        // All API-driven org/membership creation happens first; the manual membership inserts are
        // done last so their hand-computed ids cannot collide with a Hibernate-assigned one.
        adminToken = login(mockMvc, om, ADMIN_EMAIL, ADMIN_PASSWORD);
        acmeSlug = createOrg(adminToken, "Acme", "acme");
        acmePieceTypeId = createPieceType(adminToken, acmeSlug, "Pieza Movimiento", "relay");
        acmeSegmentTypeId = createPieceType(adminToken, acmeSlug, "Edifici amb llum", "led_segment");

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

    @Test
    @DisplayName("admin owner can create and read a port related to a piece type, with pin and typed parameters")
    void adminOwner_can_createAndReadPort() throws Exception {
        createPort(adminToken, acmeSlug, "Salida tira led 1", 21, acmePieceTypeId)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Salida tira led 1"))
                .andExpect(jsonPath("$.kind").value("relay"))
                .andExpect(jsonPath("$.config").isMap())
                .andExpect(jsonPath("$.pieceTypeId").value(acmePieceTypeId))
                .andExpect(jsonPath("$.pieceTypeName").value("Pieza Movimiento"))
                .andExpect(jsonPath("$.pin").value(21))
                .andExpect(jsonPath("$.parameters[0].name").value("channel"))
                .andExpect(jsonPath("$.parameters[0].type").value("INTEGER"))
                .andExpect(jsonPath("$.parameters[0].dynamic").value(false))
                .andExpect(jsonPath("$.parameters[0].staticValue").value("0"))
                .andExpect(jsonPath("$.parameters[1].name").value("dma"))
                .andExpect(jsonPath("$.parameters[1].staticValue").value("10"));

        mockMvc.perform(get(portsPath(acmeSlug)).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Salida tira led 1"))
                .andExpect(jsonPath("$[0].pieceTypeName").value("Pieza Movimiento"))
                .andExpect(jsonPath("$[0].pin").value(21))
                .andExpect(jsonPath("$[0].parameters[0].staticValue").value("0"))
                .andExpect(jsonPath("$[0].parameters[1].staticValue").value("10"));
    }

    @Test
    @DisplayName("manager of an admin-owned org can create ports")
    void manager_can_createPort() throws Exception {
        createPort(managerToken, acmeSlug, "Salida tira led 1", 21, acmePieceTypeId)
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("spectator can read but cannot create ports")
    void spectator_canRead_butCannotManage() throws Exception {
        createPort(adminToken, acmeSlug, "Salida tira led 1", 21, acmePieceTypeId)
                .andExpect(status().isCreated());

        mockMvc.perform(get(portsPath(acmeSlug)).header("Authorization", "Bearer " + spectatorToken))
                .andExpect(status().isOk());
        createPort(spectatorToken, acmeSlug, "Otra salida", 22, acmePieceTypeId)
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a plain USER member can read but cannot create ports (only OWNER/MANAGER manage)")
    void user_canRead_butCannotManage() throws Exception {
        mockMvc.perform(get(portsPath(acmeSlug)).header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk());
        createPort(userToken, acmeSlug, "Salida tira led 1", 21, acmePieceTypeId)
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the admin-owned organization exposes the ports capability flag")
    void adminOwnedOrg_exposes_capabilityFlag() throws Exception {
        mockMvc.perform(get("/organizations/" + acmeSlug).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.portsEnabled").value(true));
    }

    @Test
    @DisplayName("a non-admin-owned org cannot use or see the ports feature")
    void nonAdminOwnedOrg_isForbidden() throws Exception {
        createPort(outsiderToken, betaSlug, "Salida tira led 1", 21, acmePieceTypeId)
                .andExpect(status().isForbidden());

        mockMvc.perform(get(portsPath(betaSlug)).header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/organizations/" + betaSlug).header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.portsEnabled").value(false));
    }

    @Test
    @DisplayName("a non-member of an admin-owned org is forbidden")
    void nonMember_isForbidden() throws Exception {
        mockMvc.perform(get(portsPath(acmeSlug)).header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("creating a port without a piece type returns 400")
    void missingPieceType_returns400() throws Exception {
        Map<String, Object> body = Map.of("name", "Salida tira led 1", "kind", "relay", "pin", 21, "parameters", List.of());
        mockMvc.perform(post(portsPath(acmeSlug))
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ports.piece_type_required"));
    }

    @Test
    @DisplayName("creating a port with a piece type that does not exist in the org returns 404")
    void unknownPieceType_returns404() throws Exception {
        createPort(adminToken, acmeSlug, "Salida tira led 1", 21, 999999)
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("creating two ports with the same pin in the same org returns 409")
    void duplicatePin_returns409() throws Exception {
        createPort(adminToken, acmeSlug, "Salida tira led 1", 21, acmePieceTypeId)
                .andExpect(status().isCreated());
        createPort(adminToken, acmeSlug, "Salida tira led 2", 21, acmePieceTypeId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ports.pin_conflict"));
    }

    @Test
    @DisplayName("creating two ports with the same name in the same org returns 409")
    void duplicateName_returns409() throws Exception {
        createPort(adminToken, acmeSlug, "Salida tira led 1", 21, acmePieceTypeId)
                .andExpect(status().isCreated());
        createPort(adminToken, acmeSlug, "Salida tira led 1", 22, acmePieceTypeId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ports.name_conflict"));
    }

    @Test
    @DisplayName("soft-deleting a port frees both the name and pin slots for reuse")
    void softDelete_freesSlots() throws Exception {
        MvcResult created = createPort(adminToken, acmeSlug, "Salida tira led 1", 21, acmePieceTypeId)
                .andExpect(status().isCreated())
                .andReturn();
        Integer portId = (Integer) om.readValue(created.getResponse().getContentAsString(), Map.class).get("id");

        mockMvc.perform(delete(portsPath(acmeSlug) + "/" + portId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());

        createPort(adminToken, acmeSlug, "Salida tira led 1", 21, acmePieceTypeId)
                .andExpect(status().isCreated());
    }

    // ---------- kinds (output catalog) ----------

    @Test
    @DisplayName("a strip is an infrastructure port: no piece type, pin and config in the response")
    void strip_isInfrastructure() throws Exception {
        createStrip(adminToken, acmeSlug, "LedStrip3", 13, 1, 300)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("led_strip"))
                .andExpect(jsonPath("$.pieceTypeId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.pieceTypeName").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.pin").value(13))
                .andExpect(jsonPath("$.config.dma").value(10))
                .andExpect(jsonPath("$.config.channel").value(1))
                .andExpect(jsonPath("$.config.length").value(300))
                .andExpect(jsonPath("$.config.pin").doesNotExist());

        mockMvc.perform(get(portsPath(acmeSlug)).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].kind").value("led_strip"))
                .andExpect(jsonPath("$[0].config.length").value(300));
    }

    @Test
    @DisplayName("a segment references an existing strip by name and must fit inside it")
    void segment_referencesStrip() throws Exception {
        createStrip(adminToken, acmeSlug, "LedStrip4", 10, 0, 44).andExpect(status().isCreated());

        createSegment(adminToken, acmeSlug, "CasaDoble", "LedStrip4", 16, 43)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("led_segment"))
                .andExpect(jsonPath("$.pin").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.pieceTypeId").value(acmeSegmentTypeId))
                .andExpect(jsonPath("$.config.strip").value("LedStrip4"));

        createSegment(adminToken, acmeSlug, "Fora", "LedStrip4", 16, 44)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ports.segment_out_of_range"));

        createSegment(adminToken, acmeSlug, "Orfe", "LedStrip9", 0, 1)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ports.segment_strip_not_found"));
    }

    @Test
    @DisplayName("a strip with segments cannot be deleted")
    void strip_withSegments_cannotBeDeleted() throws Exception {
        MvcResult strip = createStrip(adminToken, acmeSlug, "LedStrip4", 10, 0, 44).andExpect(status().isCreated()).andReturn();
        Integer stripId = (Integer) om.readValue(strip.getResponse().getContentAsString(), Map.class).get("id");
        createSegment(adminToken, acmeSlug, "CasaDoble", "LedStrip4", 16, 43).andExpect(status().isCreated());

        mockMvc.perform(delete(portsPath(acmeSlug) + "/" + stripId).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ports.strip_in_use"));
    }

    @Test
    @DisplayName("a port whose piece type has another kind is rejected with ports.piece_type_kind_mismatch")
    void kindMismatch_returns400() throws Exception {
        postPort(adminToken, acmeSlug, Map.of("name", "Focos", "kind", "relay", "pieceTypeId", acmeSegmentTypeId, "pin", 27))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ports.piece_type_kind_mismatch"));
    }

    @Test
    @DisplayName("an unknown kind is rejected with ports.kind_invalid and a missing one with ports.kind_required")
    void badKind_returns400() throws Exception {
        postPort(adminToken, acmeSlug, Map.of("name", "Focos", "kind", "laser", "pieceTypeId", acmePieceTypeId, "pin", 27))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ports.kind_invalid"));
        postPort(adminToken, acmeSlug, Map.of("name", "Focos", "pieceTypeId", acmePieceTypeId, "pin", 27))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ports.kind_required"));
    }

    @Test
    @DisplayName("a motor keeps its pins in the config and they count for pin conflicts")
    void motor_pinsConflict() throws Exception {
        Integer motorTypeId = createPieceType(adminToken, acmeSlug, "Àngel", "motor");
        Map<String, Object> config = Map.of("pinForward", 3, "pinReverse", 4, "maxTimeForward", 20, "maxTimeReverse", 15);
        postPort(adminToken, acmeSlug, Map.of("name", "Angel", "kind", "motor", "pieceTypeId", motorTypeId, "config", config))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pin").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.config.pinForward").value(3));

        createPort(adminToken, acmeSlug, "Sembra", 4, acmePieceTypeId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ports.pin_conflict"));
    }

    @Test
    @DisplayName("an expander is infrastructure; relays hang from it by channel and the I2C pins get reserved (contract v3)")
    void expander_flow() throws Exception {
        postPort(adminToken, acmeSlug, Map.of("name", "exp1", "kind", "gpio_expander",
                        "config", Map.of("chip", "mcp23017", "bus", 1, "address", 32, "activeLow", true)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("gpio_expander"))
                .andExpect(jsonPath("$.pin").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.pieceTypeId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.config.address").value(32));
        MvcResult relay = postPort(adminToken, acmeSlug, Map.of("name", "Estable", "kind", "relay", "pieceTypeId", acmePieceTypeId,
                        "config", Map.of("expander", "exp1", "channel", 3)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pin").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.config.expander").value("exp1"))
                .andExpect(jsonPath("$.config.channel").value(3))
                .andReturn();
        postPort(adminToken, acmeSlug, Map.of("name", "Pou", "kind", "relay", "pieceTypeId", acmePieceTypeId,
                        "config", Map.of("expander", "exp1", "channel", 3)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ports.expander_channel_conflict"));
        postPort(adminToken, acmeSlug, Map.of("name", "Pou", "kind", "relay", "pieceTypeId", acmePieceTypeId, "pin", 26,
                        "config", Map.of("expander", "exp1", "channel", 4)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ports.pin_or_expander"));
        postPort(adminToken, acmeSlug, Map.of("name", "Pou", "kind", "relay", "pieceTypeId", acmePieceTypeId,
                        "config", Map.of("expander", "exp9", "channel", 4)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ports.expander_not_found"));
        createPort(adminToken, acmeSlug, "Sembra", 2, acmePieceTypeId)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ports.i2c_pin_reserved"));
        postPort(adminToken, acmeSlug, Map.of("name", "exp2", "kind", "gpio_expander", "config", Map.of("chip", "mcp23017", "address", 32)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ports.expander_address_conflict"));

        Integer expanderId = jdbcTemplate.queryForObject("SELECT id FROM ports WHERE name = 'exp1'", Integer.class);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(portsPath(acmeSlug) + "/" + expanderId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ports.expander_in_use"));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(portsPath(acmeSlug) + "/" + expanderId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("name", "expansor"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("expansor"));
        assertThat(relay.getResponse().getContentAsString()).contains("\"Estable\"");
        mockMvc.perform(get(portsPath(acmeSlug)).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'Estable')].config.expander").value(org.hamcrest.Matchers.contains("expansor")));
    }

    @Test
    @DisplayName("a measured sensor names a model, takes its wiring and reserves the I2C bus; a bad config is sensor_config_invalid")
    void measuredSensor_flow() throws Exception {
        postPort(adminToken, acmeSlug, Map.of("name", "Angel-límit", "kind", "sensor",
                        "config", Map.of("source", "measured", "model", "vl53l0x", "threshold", 20, "activeWhen", "below", "hysteresis", 2)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pin").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.config.model").value("vl53l0x"))
                .andExpect(jsonPath("$.config.threshold").value(20));
        postPort(adminToken, acmeSlug, Map.of("name", "Termo", "kind", "sensor", "pin", 4,
                        "config", Map.of("source", "measured", "model", "ds18b20", "threshold", 40, "activeWhen", "above")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pin").value(4));
        postPort(adminToken, acmeSlug, Map.of("name", "Calor", "kind", "sensor",
                        "config", Map.of("source", "measured", "model", "vl53l0x", "threshold", 500, "activeWhen", "below")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ports.sensor_config_invalid"))
                .andExpect(jsonPath("$.params.reason").value("threshold_out_of_range"))
                .andExpect(jsonPath("$.params.unit").value("cm"));
        postPort(adminToken, acmeSlug, Map.of("name", "Pastor-límit", "kind", "sensor",
                        "config", Map.of("source", "measured", "model", "vl53l0x", "threshold", 20, "activeWhen", "below")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ports.i2c_address_conflict"))
                .andExpect(jsonPath("$.params.port").value("Angel-límit"));
        createPort(adminToken, acmeSlug, "Sembra", 3, acmePieceTypeId)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ports.i2c_pin_reserved"));
    }

    @Test
    @DisplayName("a moving head fits the DMX universe and does not overlap another; a motor needs maxTimeForward (contract v3)")
    void movingHead_andMotorRules() throws Exception {
        Integer headTypeId = createPieceType(adminToken, acmeSlug, "Cabezal", "moving_head");
        postPort(adminToken, acmeSlug, Map.of("name", "Foco", "kind", "moving_head", "pieceTypeId", headTypeId,
                        "config", Map.of("profile", "varytec-hero-wash-712z", "address", 1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("moving_head"))
                .andExpect(jsonPath("$.config.address").value(1));
        postPort(adminToken, acmeSlug, Map.of("name", "Foco2", "kind", "moving_head", "pieceTypeId", headTypeId,
                        "config", Map.of("profile", "varytec-hero-wash-712z", "address", 15)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ports.dmx_address_conflict"));
        postPort(adminToken, acmeSlug, Map.of("name", "Foco2", "kind", "moving_head", "pieceTypeId", headTypeId,
                        "config", Map.of("profile", "varytec-hero-wash-712z", "address", 500)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ports.dmx_address_range"));
        postPort(adminToken, acmeSlug, Map.of("name", "Foco2", "kind", "moving_head", "pieceTypeId", headTypeId,
                        "config", Map.of("profile", "varytec-hero-wash-712z", "address", 16)))
                .andExpect(status().isCreated());
        Integer motorTypeId = createPieceType(adminToken, acmeSlug, "Àngel", "motor");
        postPort(adminToken, acmeSlug, Map.of("name", "Angel", "kind", "motor", "pieceTypeId", motorTypeId,
                        "config", Map.of("pinForward", 27, "pinReverse", 17, "maxTimeReverse", 18)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ports.config_missing_key"))
                .andExpect(jsonPath("$.params.key").value("maxTimeForward"));
        postPort(adminToken, acmeSlug, Map.of("name", "Video", "kind", "video", "pieceTypeId", acmePieceTypeId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ports.kind_invalid"));
    }

    @Test
    @DisplayName("the kind of a port cannot be changed")
    void kind_isImmutable() throws Exception {
        MvcResult created = createPort(adminToken, acmeSlug, "Focos", 27, acmePieceTypeId).andExpect(status().isCreated()).andReturn();
        Integer portId = (Integer) om.readValue(created.getResponse().getContentAsString(), Map.class).get("id");

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(portsPath(acmeSlug) + "/" + portId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("kind", "sensor"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ports.kind_immutable"));
    }

    @Test
    @DisplayName("the piece type exposes its kind")
    void pieceType_exposesKind() throws Exception {
        mockMvc.perform(get("/organizations/" + acmeSlug + "/piece-types/" + acmeSegmentTypeId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("led_segment"));
    }

    // ---------- helpers ----------

    private String portsPath(String slug) {
        return "/organizations/" + slug + "/ports";
    }

    private ResultActions createPort(String token, String slug, String name, int pin, int pieceTypeId)
            throws Exception {
        Map<String, Object> channel = Map.of(
                "name", "channel", "displayName", "Channel", "type", "INTEGER", "required", true,
                "dynamic", false, "staticValue", "0");
        Map<String, Object> dma = Map.of(
                "name", "dma", "displayName", "DMA", "type", "INTEGER", "required", true,
                "dynamic", false, "staticValue", "10");
        Map<String, Object> body = Map.of(
                "name", name, "kind", "relay", "pieceTypeId", pieceTypeId, "pin", pin,
                "parameters", List.of(channel, dma));
        return postPort(token, slug, body);
    }

    private ResultActions postPort(String token, String slug, Map<String, Object> body) throws Exception {
        return mockMvc.perform(post(portsPath(slug))
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(body)));
    }

    private ResultActions createStrip(String token, String slug, String name, int pin, int channel, int length)
            throws Exception {
        Map<String, Object> config = Map.of("dma", 10, "channel", channel, "length", length);
        return postPort(token, slug, Map.of("name", name, "kind", "led_strip", "pin", pin, "config", config));
    }

    private ResultActions createSegment(String token, String slug, String name, String strip, int first, int last)
            throws Exception {
        Map<String, Object> config = Map.of("strip", strip, "first", first, "last", last);
        return postPort(token, slug, Map.of("name", name, "kind", "led_segment", "pieceTypeId", acmeSegmentTypeId,
                "config", config));
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

    private Integer createPieceType(String token, String slug, String name, String kind) throws Exception {
        MvcResult r = mockMvc.perform(post("/organizations/" + slug + "/piece-types")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("name", name, "kind", kind))))
                .andExpect(status().isCreated())
                .andReturn();
        return (Integer) om.readValue(r.getResponse().getContentAsString(), Map.class).get("id");
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
