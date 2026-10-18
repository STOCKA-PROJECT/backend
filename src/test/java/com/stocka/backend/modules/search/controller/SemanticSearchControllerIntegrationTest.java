package com.stocka.backend.modules.search.controller;

import static com.stocka.backend.modules.organizations.IntegrationTestSupport.ADMIN_EMAIL;
import static com.stocka.backend.modules.organizations.IntegrationTestSupport.ADMIN_PASSWORD;
import static com.stocka.backend.modules.organizations.IntegrationTestSupport.buildMockMvc;
import static com.stocka.backend.modules.organizations.IntegrationTestSupport.login;
import static com.stocka.backend.modules.organizations.IntegrationTestSupport.signupAndLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.web.context.WebApplicationContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocka.backend.modules.organizations.IntegrationTestSupport;

/**
 * HTTP contract of the semantic search endpoints with the feature off (the test profile has no
 * XEYE key): who can read the status, who can ask for a rebuild, and the 409 every rebuild gets
 * while the server is not configured. The ranking itself is covered by
 * {@code PieceSemanticListIntegrationTest}.
 */
@SpringBootTest
@DisplayName("Semantic search endpoints (integration)")
class SemanticSearchControllerIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final ObjectMapper om = new ObjectMapper();

    private MockMvc mockMvc;
    private String adminToken;
    private String managerToken;
    private String userToken;
    private String spectatorToken;
    private String outsiderToken;
    private String orgSlug;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = buildMockMvc(context);
        IntegrationTestSupport.cleanDatabase(jdbcTemplate);

        adminToken = login(mockMvc, om, ADMIN_EMAIL, ADMIN_PASSWORD);
        MvcResult created = mockMvc.perform(post("/organizations")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("name", "Acme", "slug", "acme"))))
                .andExpect(status().isOk())
                .andReturn();
        Map<?, ?> org = om.readValue(created.getResponse().getContentAsString(), Map.class);
        orgSlug = (String) org.get("slug");
        Integer orgId = (Integer) org.get("id");

        managerToken = signupAndLogin(mockMvc, om, jdbcTemplate, "manager@test.com", "manager");
        userToken = signupAndLogin(mockMvc, om, jdbcTemplate, "user@test.com", "userp");
        spectatorToken = signupAndLogin(mockMvc, om, jdbcTemplate, "spect@test.com", "spect");
        outsiderToken = signupAndLogin(mockMvc, om, jdbcTemplate, "out@test.com", "outsider");
        addMember(orgId, userIdOf("manager@test.com"), "MANAGER");
        addMember(orgId, userIdOf("user@test.com"), "USER");
        addMember(orgId, userIdOf("spect@test.com"), "SPECTATOR");
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

    @Nested
    @DisplayName("GET /organizations/{slug}/search/status")
    class Status {

        @Test
        @DisplayName("any member, even a spectator, reads the status; the feature is off in tests")
        void spectatorReads() throws Exception {
            mockMvc.perform(get("/organizations/" + orgSlug + "/search/status")
                            .header("Authorization", "Bearer " + spectatorToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.enabled").value(false))
                    .andExpect(jsonPath("$.indexed").value(false))
                    .andExpect(jsonPath("$.indexedPieces").value(0))
                    .andExpect(jsonPath("$.totalPieces").value(0))
                    .andExpect(jsonPath("$.trainingPending").value(false))
                    .andExpect(jsonPath("$.lastTrainingLaunchedAt").doesNotExist());
        }

        @Test
        @DisplayName("counts the active pieces of the organization")
        void countsPieces() throws Exception {
            mockMvc.perform(post("/organizations/" + orgSlug + "/pieces")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of("name", "Pastor"))))
                    .andExpect(status().isCreated());

            mockMvc.perform(get("/organizations/" + orgSlug + "/search/status")
                            .header("Authorization", "Bearer " + managerToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalPieces").value(1))
                    .andExpect(jsonPath("$.indexedPieces").value(0));
        }

        @Test
        @DisplayName("is forbidden for non-members")
        void outsider() throws Exception {
            mockMvc.perform(get("/organizations/" + orgSlug + "/search/status")
                            .header("Authorization", "Bearer " + outsiderToken))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("requires authentication")
        void anonymous() throws Exception {
            mockMvc.perform(get("/organizations/" + orgSlug + "/search/status"))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("POST /organizations/{slug}/search/reindex")
    class Reindex {

        @Test
        @DisplayName("owner gets 409 search.not_configured while the server has no XEYE key")
        void ownerNotConfigured() throws Exception {
            mockMvc.perform(post("/organizations/" + orgSlug + "/search/reindex")
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("search.not_configured"));
        }

        @Test
        @DisplayName("manager may ask for a rebuild")
        void manager() throws Exception {
            mockMvc.perform(post("/organizations/" + orgSlug + "/search/reindex")
                            .header("Authorization", "Bearer " + managerToken))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("search.not_configured"));
        }

        @Test
        @DisplayName("user and spectator are forbidden")
        void userAndSpectator() throws Exception {
            mockMvc.perform(post("/organizations/" + orgSlug + "/search/reindex")
                            .header("Authorization", "Bearer " + userToken))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/organizations/" + orgSlug + "/search/reindex")
                            .header("Authorization", "Bearer " + spectatorToken))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("non-members are forbidden")
        void outsider() throws Exception {
            mockMvc.perform(post("/organizations/" + orgSlug + "/search/reindex")
                            .header("Authorization", "Bearer " + outsiderToken))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("POST /admin/search/reindex")
    class AdminReindex {

        @Test
        @DisplayName("global admin gets 409 search.not_configured while the server has no XEYE key")
        void adminNotConfigured() throws Exception {
            mockMvc.perform(post("/admin/search/reindex")
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("search.not_configured"));
        }

        @Test
        @DisplayName("regular users are forbidden")
        void regularUser() throws Exception {
            mockMvc.perform(post("/admin/search/reindex")
                            .header("Authorization", "Bearer " + managerToken))
                    .andExpect(status().isForbidden());
        }
    }
}
