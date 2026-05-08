package com.stocka.backend.modules.organizations.controller;

import static com.stocka.backend.modules.organizations.IntegrationTestSupport.ADMIN_EMAIL;
import static com.stocka.backend.modules.organizations.IntegrationTestSupport.ADMIN_PASSWORD;
import static com.stocka.backend.modules.organizations.IntegrationTestSupport.buildMockMvc;
import static com.stocka.backend.modules.organizations.IntegrationTestSupport.login;
import static com.stocka.backend.modules.organizations.IntegrationTestSupport.signupAndLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
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
import com.stocka.backend.modules.organizations.entity.OrganizationInvitation;
import com.stocka.backend.modules.organizations.repository.OrganizationInvitationRepository;

@SpringBootTest
@DisplayName("InvitationController (integration)")
class InvitationControllerIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private OrganizationInvitationRepository invitationRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final ObjectMapper om = new ObjectMapper();

    private MockMvc mockMvc;
    private String adminToken;
    private String inviteeToken;

    private Integer orgId;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = buildMockMvc(context);
        cleanDatabase();

        adminToken = login(mockMvc, om, ADMIN_EMAIL, ADMIN_PASSWORD);
        inviteeToken = signupAndLogin(mockMvc, om, jdbcTemplate, "invitee@test.com", "invitee");

        var result = mockMvc.perform(post("/organizations")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("name", "Acme", "slug", "acme"))))
                .andExpect(status().isOk())
                .andReturn();
        Map<?, ?> body = om.readValue(result.getResponse().getContentAsString(), Map.class);
        orgId = (Integer) body.get("id");
    }

    private void cleanDatabase() {
        IntegrationTestSupport.cleanDatabase(jdbcTemplate);
    }

    private String createInvitation(String email, String role) throws Exception {
        var result = mockMvc.perform(post("/organizations/" + orgId + "/invitations")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("email", email, "role", role))))
                .andExpect(status().isOk())
                .andReturn();
        Map<?, ?> body = om.readValue(result.getResponse().getContentAsString(), Map.class);
        return (String) body.get("token");
    }

    @Test
    @DisplayName("GET /invitations/me — lists my pending invitations")
    void listMine() throws Exception {
        createInvitation("invitee@test.com", "USER");
        mockMvc.perform(get("/invitations/me")
                        .header("Authorization", "Bearer " + inviteeToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @DisplayName("GET /invitations/me — empty when none")
    void listMine_empty() throws Exception {
        mockMvc.perform(get("/invitations/me")
                        .header("Authorization", "Bearer " + inviteeToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("GET /invitations/me?includeHistory=true — includes accepted/rejected")
    void listMine_includeHistory() throws Exception {
        // Create + reject one
        String tokenRejected = createInvitation("invitee@test.com", "USER");
        mockMvc.perform(post("/invitations/" + tokenRejected + "/reject")
                        .header("Authorization", "Bearer " + inviteeToken))
                .andExpect(status().isOk());

        // Create another one (still pending)
        createInvitation("invitee@test.com", "MANAGER");

        // Without flag → only pending
        mockMvc.perform(get("/invitations/me")
                        .header("Authorization", "Bearer " + inviteeToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("PENDING"))
                .andExpect(jsonPath("$[0].token").isNotEmpty());

        // With flag → both, pending first, token only on pending
        mockMvc.perform(get("/invitations/me?includeHistory=true")
                        .header("Authorization", "Bearer " + inviteeToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].status").value("PENDING"))
                .andExpect(jsonPath("$[0].token").isNotEmpty())
                .andExpect(jsonPath("$[1].status").value("REJECTED"))
                .andExpect(jsonPath("$[1].token").isEmpty())
                .andExpect(jsonPath("$[1].createdAt").isNotEmpty());
    }

    @Test
    @DisplayName("POST /accept 200 — creates membership")
    void accept_ok() throws Exception {
        String token = createInvitation("invitee@test.com", "USER");
        mockMvc.perform(post("/invitations/" + token + "/accept")
                        .header("Authorization", "Bearer " + inviteeToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));

        // Now invitee can list members of the org
        mockMvc.perform(get("/organizations/" + orgId + "/members")
                        .header("Authorization", "Bearer " + inviteeToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /accept 404 — token does not exist")
    void accept_404() throws Exception {
        mockMvc.perform(post("/invitations/nope/accept")
                        .header("Authorization", "Bearer " + inviteeToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /accept 403 — invitation email does not match actor")
    void accept_403_emailMismatch() throws Exception {
        String token = createInvitation("other@test.com", "USER");
        mockMvc.perform(post("/invitations/" + token + "/accept")
                        .header("Authorization", "Bearer " + inviteeToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /accept 410 — expired invitation")
    void accept_410_expired() throws Exception {
        String token = createInvitation("invitee@test.com", "USER");
        OrganizationInvitation inv = invitationRepository.findByToken(token).orElseThrow();
        inv.setExpiresAt(LocalDateTime.now().minusDays(1));
        invitationRepository.save(inv);

        mockMvc.perform(post("/invitations/" + token + "/accept")
                        .header("Authorization", "Bearer " + inviteeToken))
                .andExpect(status().isGone());
    }

    @Test
    @DisplayName("POST /accept 400 — already accepted")
    void accept_400_alreadyAccepted() throws Exception {
        String token = createInvitation("invitee@test.com", "USER");
        mockMvc.perform(post("/invitations/" + token + "/accept")
                        .header("Authorization", "Bearer " + inviteeToken))
                .andExpect(status().isOk());
        mockMvc.perform(post("/invitations/" + token + "/accept")
                        .header("Authorization", "Bearer " + inviteeToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /reject 200 — marks REJECTED")
    void reject_ok() throws Exception {
        String token = createInvitation("invitee@test.com", "USER");
        mockMvc.perform(post("/invitations/" + token + "/reject")
                        .header("Authorization", "Bearer " + inviteeToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    @Test
    @DisplayName("POST /reject 403 — email mismatch")
    void reject_403() throws Exception {
        String token = createInvitation("other@test.com", "USER");
        mockMvc.perform(post("/invitations/" + token + "/reject")
                        .header("Authorization", "Bearer " + inviteeToken))
                .andExpect(status().isForbidden());
    }
}
