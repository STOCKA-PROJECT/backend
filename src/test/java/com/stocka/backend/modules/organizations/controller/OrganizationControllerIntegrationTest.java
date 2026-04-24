package com.stocka.backend.modules.organizations.controller;

import static com.stocka.backend.modules.organizations.IntegrationTestSupport.ADMIN_EMAIL;
import static com.stocka.backend.modules.organizations.IntegrationTestSupport.ADMIN_PASSWORD;
import static com.stocka.backend.modules.organizations.IntegrationTestSupport.buildMockMvc;
import static com.stocka.backend.modules.organizations.IntegrationTestSupport.login;
import static com.stocka.backend.modules.organizations.IntegrationTestSupport.signupAndLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocka.backend.modules.organizations.IntegrationTestSupport;

@SpringBootTest
@DisplayName("OrganizationController (integration)")
class OrganizationControllerIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final ObjectMapper om = new ObjectMapper();

    private MockMvc mockMvc;
    private String adminToken;
    private String userBToken;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = buildMockMvc(context);
        cleanDatabase();
        adminToken = login(mockMvc, om, ADMIN_EMAIL, ADMIN_PASSWORD);
        userBToken = signupAndLogin(mockMvc, om, "userb@test.com", "userb");
    }

    private void cleanDatabase() {
        IntegrationTestSupport.cleanDatabase(jdbcTemplate);
    }

    private Integer createOrgAs(String token, String name, String slug) throws Exception {
        var result = mockMvc.perform(post("/organizations")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("name", name, "slug", slug))))
                .andExpect(status().isOk())
                .andReturn();
        Map<?, ?> body = om.readValue(result.getResponse().getContentAsString(), Map.class);
        return (Integer) body.get("id");
    }

    @Test
    @DisplayName("POST 200 — should create org and assign actor as OWNER")
    void post_should_returnOkAndAssignOwner() throws Exception {
        mockMvc.perform(post("/organizations")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("name", "Acme", "slug", "acme"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.name").value("Acme"))
                .andExpect(jsonPath("$.slug").value("acme"))
                .andExpect(jsonPath("$.currentUserRole").value("OWNER"));
    }

    @Test
    @DisplayName("POST 400 — should reject invalid slug format")
    void post_should_return400_when_slugInvalid() throws Exception {
        mockMvc.perform(post("/organizations")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("name", "Acme", "slug", "BAD!"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST 400 — should reject reserved slug")
    void post_should_return400_when_slugReserved() throws Exception {
        mockMvc.perform(post("/organizations")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("name", "Acme", "slug", "admin"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST 409 — should reject duplicate slug")
    void post_should_return409_when_slugDuplicated() throws Exception {
        createOrgAs(adminToken, "Acme", "acme");
        mockMvc.perform(post("/organizations")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("name", "Other", "slug", "acme"))))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("POST 401 — without auth")
    void post_should_return401_when_noAuth() throws Exception {
        mockMvc.perform(post("/organizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("name", "Acme", "slug", "acme"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET / — should list user's organizations")
    void get_listMyOrganizations() throws Exception {
        createOrgAs(adminToken, "A", "org-a");
        createOrgAs(adminToken, "B", "org-b");
        mockMvc.perform(get("/organizations")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    @DisplayName("GET / — empty list when user has no orgs")
    void get_emptyList_when_noOrgs() throws Exception {
        mockMvc.perform(get("/organizations")
                        .header("Authorization", "Bearer " + userBToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("GET /{id} 200 — member can read")
    void get_one_member() throws Exception {
        Integer id = createOrgAs(adminToken, "Acme", "acme");
        mockMvc.perform(get("/organizations/" + id)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentUserRole").value("OWNER"));
    }

    @Test
    @DisplayName("GET /{id} 403 — non-member cannot read")
    void get_one_403_for_nonMember() throws Exception {
        Integer id = createOrgAs(adminToken, "Acme", "acme");
        mockMvc.perform(get("/organizations/" + id)
                        .header("Authorization", "Bearer " + userBToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /{id} 403 — non-existent org returns forbidden (PreAuthorize)")
    void get_one_403_for_unknown() throws Exception {
        mockMvc.perform(get("/organizations/9999")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PATCH /{id} 200 — OWNER updates name")
    void patch_owner_updatesName() throws Exception {
        Integer id = createOrgAs(adminToken, "Acme", "acme");
        mockMvc.perform(patch("/organizations/" + id)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("name", "New Name"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("New Name"));
    }

    @Test
    @DisplayName("PATCH /{id} 200 — OWNER updates slug")
    void patch_owner_updatesSlug() throws Exception {
        Integer id = createOrgAs(adminToken, "Acme", "acme");
        mockMvc.perform(patch("/organizations/" + id)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("slug", "new-slug"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("new-slug"));
    }

    @Test
    @DisplayName("PATCH /{id} 400 — reserved slug")
    void patch_reservedSlug() throws Exception {
        Integer id = createOrgAs(adminToken, "Acme", "acme");
        mockMvc.perform(patch("/organizations/" + id)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("slug", "api"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PATCH /{id} 403 — non-OWNER")
    void patch_nonOwner() throws Exception {
        Integer id = createOrgAs(adminToken, "Acme", "acme");
        mockMvc.perform(patch("/organizations/" + id)
                        .header("Authorization", "Bearer " + userBToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("name", "X"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("DELETE /{id} 204 — OWNER soft-deletes org")
    void delete_owner() throws Exception {
        Integer id = createOrgAs(adminToken, "Acme", "acme");
        mockMvc.perform(delete("/organizations/" + id)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/organizations/" + id)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("DELETE /{id} 403 — non-OWNER cannot delete")
    void delete_nonOwner() throws Exception {
        Integer id = createOrgAs(adminToken, "Acme", "acme");
        mockMvc.perform(delete("/organizations/" + id)
                        .header("Authorization", "Bearer " + userBToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("ADMIN bypass — non-member ADMIN can read any org")
    void admin_canReadAny() throws Exception {
        // Admin is global ADMIN — also creates the org so it's automatically OWNER.
        // Need a non-admin OWNER to test bypass. Create an org as userB then read as admin.
        Integer id = createOrgAs(userBToken, "Acme", "acme");
        mockMvc.perform(get("/organizations/" + id)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }
}
