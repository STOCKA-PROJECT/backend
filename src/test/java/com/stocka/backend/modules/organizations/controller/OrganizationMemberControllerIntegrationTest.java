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
import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.organizations.entity.OrganizationMember;
import com.stocka.backend.modules.organizations.entity.OrganizationRoleEnum;
import com.stocka.backend.modules.organizations.repository.OrganizationMemberRepository;
import com.stocka.backend.modules.organizations.repository.OrganizationRepository;
import com.stocka.backend.modules.users.entity.User;
import com.stocka.backend.modules.users.repository.UserRepository;

@SpringBootTest
@DisplayName("OrganizationMemberController (integration)")
class OrganizationMemberControllerIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private OrganizationMemberRepository memberRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final ObjectMapper om = new ObjectMapper();

    private MockMvc mockMvc;
    private String adminToken;
    private String managerToken;
    private String userToken;
    private String outsiderToken;

    private Integer orgId;
    private String orgSlug;
    private Integer adminMemberId;
    private Integer managerMemberId;
    private Integer userMemberId;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = buildMockMvc(context);
        cleanDatabase();

        adminToken = login(mockMvc, om, ADMIN_EMAIL, ADMIN_PASSWORD);
        managerToken = signupAndLogin(mockMvc, om, jdbcTemplate, "manager@test.com", "manager");
        userToken = signupAndLogin(mockMvc, om, jdbcTemplate, "user@test.com", "regularuser");
        outsiderToken = signupAndLogin(mockMvc, om, jdbcTemplate, "outsider@test.com", "outsider");

        // Create org as admin → admin becomes OWNER
        Map<String, Object> org = createOrg();
        orgId = (Integer) org.get("id");
        orgSlug = (String) org.get("slug");

        // Manually add manager and user as memberships
        Organization orgEntity = organizationRepository.findById(orgId).orElseThrow();
        adminMemberId = memberRepository.findByOrganization(orgEntity).get(0).getId();
        managerMemberId = memberRepository.save(new OrganizationMember()
                .setUser(userByEmail("manager@test.com"))
                .setOrganization(orgEntity)
                .setRole(OrganizationRoleEnum.MANAGER)).getId();
        userMemberId = memberRepository.save(new OrganizationMember()
                .setUser(userByEmail("user@test.com"))
                .setOrganization(orgEntity)
                .setRole(OrganizationRoleEnum.USER)).getId();
    }

    private void cleanDatabase() {
        IntegrationTestSupport.cleanDatabase(jdbcTemplate);
    }

    private User userByEmail(String email) {
        return userRepository.findByEmail(email).orElseThrow();
    }

    private Map<String, Object> createOrg() throws Exception {
        var result = mockMvc.perform(post("/organizations")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("name", "Acme", "slug", "acme"))))
                .andExpect(status().isOk())
                .andReturn();
        return om.readValue(result.getResponse().getContentAsString(), Map.class);
    }

    @Test
    @DisplayName("GET /members 200 — member can list")
    void list_member() throws Exception {
        mockMvc.perform(get("/organizations/" + orgSlug + "/members")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    @DisplayName("GET /members 403 — outsider")
    void list_outsider() throws Exception {
        mockMvc.perform(get("/organizations/" + orgSlug + "/members")
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PATCH /members/{id} 200 — OWNER promotes USER → MANAGER")
    void patch_owner_promotes() throws Exception {
        mockMvc.perform(patch("/organizations/" + orgSlug + "/members/" + userMemberId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("role", "MANAGER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("MANAGER"));
    }

    @Test
    @DisplayName("PATCH /members/{id} 403 — actor cannot operate on own membership")
    void patch_self_403() throws Exception {
        mockMvc.perform(patch("/organizations/" + orgSlug + "/members/" + adminMemberId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("role", "USER"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("no puedes operar sobre tu propia membresía"));
    }

    @Test
    @DisplayName("PATCH /members/{id} 403 — MANAGER cannot change roles")
    void patch_manager_403() throws Exception {
        mockMvc.perform(patch("/organizations/" + orgSlug + "/members/" + userMemberId)
                        .header("Authorization", "Bearer " + managerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("role", "MANAGER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("DELETE /members/{id} 204 — OWNER removes USER")
    void delete_owner_removesUser() throws Exception {
        mockMvc.perform(delete("/organizations/" + orgSlug + "/members/" + userMemberId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("DELETE /members/{id} 204 — MANAGER removes USER")
    void delete_manager_removesUser() throws Exception {
        mockMvc.perform(delete("/organizations/" + orgSlug + "/members/" + userMemberId)
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("DELETE /members/{id} 403 — MANAGER cannot remove MANAGER")
    void delete_manager_cannotRemoveManager() throws Exception {
        // Create another manager
        Organization orgEntity = organizationRepository.findById(orgId).orElseThrow();
        signupAndLogin(mockMvc, om, jdbcTemplate, "mgr2@test.com", "mgr2");
        Integer mgr2Id = memberRepository.save(new OrganizationMember()
                .setUser(userByEmail("mgr2@test.com"))
                .setOrganization(orgEntity)
                .setRole(OrganizationRoleEnum.MANAGER)).getId();

        mockMvc.perform(delete("/organizations/" + orgSlug + "/members/" + mgr2Id)
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("DELETE /members/{id} 403 — USER cannot remove anyone")
    void delete_user_403() throws Exception {
        mockMvc.perform(delete("/organizations/" + orgSlug + "/members/" + managerMemberId)
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("DELETE /members/{id} 403 — actor cannot operate on own membership")
    void delete_self_403() throws Exception {
        mockMvc.perform(delete("/organizations/" + orgSlug + "/members/" + adminMemberId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("no puedes operar sobre tu propia membresía"));
    }

    @Test
    @DisplayName("DELETE /members/me 204 — USER leaves")
    void leave_user() throws Exception {
        mockMvc.perform(delete("/organizations/" + orgSlug + "/members/me")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("DELETE /members/me 409 — last OWNER cannot leave")
    void leave_lastOwner_409() throws Exception {
        mockMvc.perform(delete("/organizations/" + orgSlug + "/members/me")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isConflict());
    }
}
