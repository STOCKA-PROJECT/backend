package com.stocka.backend.modules.piecetypes.controller;

import static com.stocka.backend.modules.organizations.IntegrationTestSupport.ADMIN_EMAIL;
import static com.stocka.backend.modules.organizations.IntegrationTestSupport.ADMIN_PASSWORD;
import static com.stocka.backend.modules.organizations.IntegrationTestSupport.buildMockMvc;
import static com.stocka.backend.modules.organizations.IntegrationTestSupport.login;
import static com.stocka.backend.modules.organizations.IntegrationTestSupport.signupAndLogin;
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
import org.springframework.web.context.WebApplicationContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocka.backend.modules.organizations.IntegrationTestSupport;

/**
 * End-to-end coverage for the private piece-type actions feature, which is only available on
 * organizations whose owner is a global admin.
 *
 * <p>Fixture: the seeded global admin ({@link com.stocka.backend.modules.organizations.IntegrationTestSupport#ADMIN_EMAIL})
 * owns "Acme" (an admin-owned org) and a regular user owns "Beta" (not admin-owned).
 */
@SpringBootTest
@DisplayName("PieceTypeAction feature (integration)")
class PieceTypeActionControllerIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final ObjectMapper om = new ObjectMapper();

    private MockMvc mockMvc;
    private String adminToken;
    private String managerToken;
    private String outsiderToken;
    private String acmeSlug;
    private Integer acmeTypeId;
    private String betaSlug;
    private Integer betaTypeId;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = buildMockMvc(context);
        IntegrationTestSupport.cleanDatabase(jdbcTemplate);

        // All API-driven org/membership creation happens first; the manual membership insert is
        // done last so its hand-computed id cannot collide with a Hibernate-assigned one.
        adminToken = login(mockMvc, om, ADMIN_EMAIL, ADMIN_PASSWORD);
        acmeSlug = createOrg(adminToken, "Acme", "acme");
        acmeTypeId = createPieceType(adminToken, acmeSlug, "Pieza Movimiento");

        outsiderToken = signupAndLogin(mockMvc, om, jdbcTemplate, "out@test.com", "outsider");
        betaSlug = createOrg(outsiderToken, "Beta", "beta");
        betaTypeId = createPieceType(outsiderToken, betaSlug, "Caja");

        managerToken = signupAndLogin(mockMvc, om, jdbcTemplate, "manager@test.com", "manager");
        Integer acmeId = jdbcTemplate.queryForObject(
                "SELECT id FROM organizations WHERE slug = ?", Integer.class, "acme");
        Integer managerUserId = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE email = ?", Integer.class, "manager@test.com");
        addMember(acmeId, managerUserId, "MANAGER");
    }

    @Test
    @DisplayName("admin owner can create and read an action with a typed parameter")
    void adminOwner_can_createAndReadAction() throws Exception {
        createAction(adminToken, acmeSlug, acmeTypeId)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("encender"))
                .andExpect(jsonPath("$.parameters[0].name").value("tiempo"))
                .andExpect(jsonPath("$.parameters[0].type").value("INTEGER"))
                .andExpect(jsonPath("$.parameters[0].required").value(true))
                .andExpect(jsonPath("$.parameters[0].dynamic").value(true));

        mockMvc.perform(get(actionsPath(acmeSlug, acmeTypeId))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("encender"));
    }

    @Test
    @DisplayName("manager of an admin-owned org can create actions")
    void manager_can_createAction() throws Exception {
        createAction(managerToken, acmeSlug, acmeTypeId).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("the admin-owned organization exposes the actions capability flag")
    void adminOwnedOrg_exposes_capabilityFlag() throws Exception {
        mockMvc.perform(get("/organizations/" + acmeSlug)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pieceTypeActionsEnabled").value(true));
    }

    @Test
    @DisplayName("a non-admin-owned org cannot use or see the actions feature")
    void nonAdminOwnedOrg_isForbidden() throws Exception {
        createAction(outsiderToken, betaSlug, betaTypeId).andExpect(status().isForbidden());

        mockMvc.perform(get(actionsPath(betaSlug, betaTypeId))
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/organizations/" + betaSlug)
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pieceTypeActionsEnabled").value(false));
    }

    // ---------- helpers ----------

    private String actionsPath(String slug, Integer typeId) {
        return "/organizations/" + slug + "/piece-types/" + typeId + "/actions";
    }

    private org.springframework.test.web.servlet.ResultActions createAction(
            String token, String slug, Integer typeId
    ) throws Exception {
        Map<String, Object> param = Map.of(
                "name", "tiempo", "displayName", "Tiempo", "type", "INTEGER",
                "required", true, "dynamic", true);
        Map<String, Object> body = Map.of(
                "name", "encender", "displayName", "Encender", "parameters", List.of(param));
        return mockMvc.perform(post(actionsPath(slug, typeId))
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

    private void addMember(Integer orgId, Integer userId, String role) {
        Long nextId = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(id), 0) + 1 FROM organization_members", Long.class);
        jdbcTemplate.update(
                "INSERT INTO organization_members (id, user_id, organization_id, role, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                nextId, userId, orgId, role);
    }
}
