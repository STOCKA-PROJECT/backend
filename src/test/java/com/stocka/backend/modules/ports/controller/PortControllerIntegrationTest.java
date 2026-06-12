package com.stocka.backend.modules.ports.controller;

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
    private String betaSlug;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = buildMockMvc(context);
        IntegrationTestSupport.cleanDatabase(jdbcTemplate);

        // All API-driven org/membership creation happens first; the manual membership inserts are
        // done last so their hand-computed ids cannot collide with a Hibernate-assigned one.
        adminToken = login(mockMvc, om, ADMIN_EMAIL, ADMIN_PASSWORD);
        acmeSlug = createOrg(adminToken, "Acme", "acme");
        acmePieceTypeId = createPieceType(adminToken, acmeSlug, "Pieza Movimiento");

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
                .andExpect(jsonPath("$.pieceTypeId").value(acmePieceTypeId))
                .andExpect(jsonPath("$.pieceTypeName").value("Pieza Movimiento"))
                .andExpect(jsonPath("$.pin").value(21))
                .andExpect(jsonPath("$.parameters[0].name").value("channel"))
                .andExpect(jsonPath("$.parameters[0].type").value("INTEGER"))
                .andExpect(jsonPath("$.parameters[1].name").value("dma"));

        mockMvc.perform(get(portsPath(acmeSlug)).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Salida tira led 1"))
                .andExpect(jsonPath("$[0].pieceTypeName").value("Pieza Movimiento"))
                .andExpect(jsonPath("$[0].pin").value(21));
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
        Map<String, Object> body = Map.of("name", "Salida tira led 1", "pin", 21, "parameters", List.of());
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

    // ---------- helpers ----------

    private String portsPath(String slug) {
        return "/organizations/" + slug + "/ports";
    }

    private ResultActions createPort(String token, String slug, String name, int pin, int pieceTypeId)
            throws Exception {
        Map<String, Object> channel = Map.of(
                "name", "channel", "displayName", "Channel", "type", "INTEGER", "required", true);
        Map<String, Object> dma = Map.of(
                "name", "dma", "displayName", "DMA", "type", "INTEGER", "required", true);
        Map<String, Object> body = Map.of(
                "name", name, "pieceTypeId", pieceTypeId, "pin", pin, "parameters", List.of(channel, dma));
        return mockMvc.perform(post(portsPath(slug))
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(body)));
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

    private Integer createPieceType(String token, String slug, String name) throws Exception {
        MvcResult r = mockMvc.perform(post("/organizations/" + slug + "/piece-types")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("name", name))))
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
