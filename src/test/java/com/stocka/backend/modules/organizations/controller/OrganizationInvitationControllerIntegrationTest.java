package com.stocka.backend.modules.organizations.controller;

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

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
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
@TestPropertySource(properties = "app.organization.max-pending-invitations=2")
@DisplayName("OrganizationInvitationController (integration)")
class OrganizationInvitationControllerIntegrationTest {

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

    private Integer orgId;
    private String orgSlug;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = buildMockMvc(context);
        cleanDatabase();

        adminToken = login(mockMvc, om, ADMIN_EMAIL, ADMIN_PASSWORD);
        managerToken = signupAndLogin(mockMvc, om, jdbcTemplate, "manager@test.com", "manager");
        userToken = signupAndLogin(mockMvc, om, jdbcTemplate, "user@test.com", "regularuser");

        var result = mockMvc.perform(post("/organizations")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("name", "Acme", "slug", "acme"))))
                .andExpect(status().isOk())
                .andReturn();
        Map<?, ?> body = om.readValue(result.getResponse().getContentAsString(), Map.class);
        orgId = (Integer) body.get("id");
        orgSlug = (String) body.get("slug");

        Organization orgEntity = organizationRepository.findById(orgId).orElseThrow();
        memberRepository.save(new OrganizationMember()
                .setUser(userByEmail("manager@test.com"))
                .setOrganization(orgEntity)
                .setRole(OrganizationRoleEnum.MANAGER));
        memberRepository.save(new OrganizationMember()
                .setUser(userByEmail("user@test.com"))
                .setOrganization(orgEntity)
                .setRole(OrganizationRoleEnum.USER));
    }

    private void cleanDatabase() {
        IntegrationTestSupport.cleanDatabase(jdbcTemplate);
    }

    private User userByEmail(String email) {
        return userRepository.findByEmail(email).orElseThrow();
    }

    private void invite(String token, String email, String role, int expectedStatus) throws Exception {
        mockMvc.perform(post("/organizations/" + orgSlug + "/invitations")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("email", email, "role", role))))
                .andExpect(status().is(expectedStatus));
    }

    @Test
    @DisplayName("POST 200 — OWNER invites with USER, MANAGER and OWNER roles")
    void post_owner_anyRole() throws Exception {
        invite(adminToken, "u1@test.com", "USER", 200);
        invite(adminToken, "u2@test.com", "MANAGER", 200);
    }

    @Test
    @DisplayName("POST 200 — MANAGER invites with USER role")
    void post_manager_user() throws Exception {
        invite(managerToken, "u1@test.com", "USER", 200);
    }

    @Test
    @DisplayName("POST 403 — MANAGER cannot invite with MANAGER role")
    void post_manager_cannotInviteManager() throws Exception {
        invite(managerToken, "u1@test.com", "MANAGER", 403);
    }

    @Test
    @DisplayName("POST 403 — USER cannot invite")
    void post_user_403() throws Exception {
        invite(userToken, "u1@test.com", "USER", 403);
    }

    @Test
    @DisplayName("POST 409 — invitee is already a member")
    void post_409_alreadyMember() throws Exception {
        invite(adminToken, "user@test.com", "USER", 409);
    }

    @Test
    @DisplayName("POST 409 — duplicate pending invitation")
    void post_409_duplicatePending() throws Exception {
        invite(adminToken, "duplicate@test.com", "USER", 200);
        invite(adminToken, "duplicate@test.com", "USER", 409);
    }

    @Test
    @DisplayName("POST 429 — rate limit reached")
    void post_429_rateLimit() throws Exception {
        invite(adminToken, "a@test.com", "USER", 200);
        invite(adminToken, "b@test.com", "USER", 200);
        invite(adminToken, "c@test.com", "USER", 429);
    }

    @Test
    @DisplayName("GET 200 — OWNER lists pending")
    void get_owner_listsPending() throws Exception {
        invite(adminToken, "x@test.com", "USER", 200);
        mockMvc.perform(get("/organizations/" + orgSlug + "/invitations")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @DisplayName("GET 403 — USER cannot list pending")
    void get_user_403() throws Exception {
        mockMvc.perform(get("/organizations/" + orgSlug + "/invitations")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("DELETE 204 — OWNER cancels pending invitation")
    void delete_owner_cancels() throws Exception {
        var result = mockMvc.perform(post("/organizations/" + orgSlug + "/invitations")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("email", "x@test.com", "role", "USER"))))
                .andExpect(status().isOk())
                .andReturn();
        Map<?, ?> body = om.readValue(result.getResponse().getContentAsString(), Map.class);
        Integer invId = (Integer) body.get("id");

        mockMvc.perform(delete("/organizations/" + orgSlug + "/invitations/" + invId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("DELETE 403 — USER cannot cancel")
    void delete_user_403() throws Exception {
        var result = mockMvc.perform(post("/organizations/" + orgSlug + "/invitations")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("email", "x@test.com", "role", "USER"))))
                .andExpect(status().isOk())
                .andReturn();
        Map<?, ?> body = om.readValue(result.getResponse().getContentAsString(), Map.class);
        Integer invId = (Integer) body.get("id");

        mockMvc.perform(delete("/organizations/" + orgSlug + "/invitations/" + invId)
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST response includes the token (useful in dev)")
    void post_responseIncludesToken() throws Exception {
        mockMvc.perform(post("/organizations/" + orgSlug + "/invitations")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("email", "x@test.com", "role", "USER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());
    }
}
