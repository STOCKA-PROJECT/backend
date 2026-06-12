package com.stocka.backend.modules.auth.controller;

import static com.stocka.backend.modules.organizations.IntegrationTestSupport.buildMockMvc;
import static com.stocka.backend.modules.organizations.IntegrationTestSupport.signupAndLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.context.WebApplicationContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocka.backend.modules.organizations.IntegrationTestSupport;

/**
 * End-to-end coverage for the session handoff used to open the Timeline Editor in a new window:
 * mint a ticket while authenticated, then exchange it (public) for a fresh session.
 */
@SpringBootTest
@DisplayName("Auth handoff (integration)")
class AuthHandoffIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final ObjectMapper om = new ObjectMapper();

    private MockMvc mockMvc;
    private String token;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = buildMockMvc(context);
        IntegrationTestSupport.cleanDatabase(jdbcTemplate);
        token = signupAndLogin(mockMvc, om, jdbcTemplate, "user@test.com", "user");
    }

    @Test
    @DisplayName("an authenticated user can mint a handoff ticket and exchange it for a session")
    void handoff_roundTrip() throws Exception {
        MvcResult minted = mockMvc.perform(post("/auth/handoff")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticket").isNotEmpty())
                .andReturn();
        String ticket = (String) om.readValue(minted.getResponse().getContentAsString(), Map.class).get("ticket");

        mockMvc.perform(post("/auth/handoff/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("ticket", ticket))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.user.email").value("user@test.com"))
                .andExpect(cookie().exists("stocka_refresh"));
    }

    @Test
    @DisplayName("minting a ticket requires authentication")
    void handoff_requiresAuth() throws Exception {
        mockMvc.perform(post("/auth/handoff"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("exchanging an invalid ticket returns 401 handoff_token_invalid")
    void exchange_invalidTicket_returns401() throws Exception {
        mockMvc.perform(post("/auth/handoff/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("ticket", "not-a-real-token"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("auth.handoff_token_invalid"));
    }

    @Test
    @DisplayName("an access token cannot be used as a handoff ticket (wrong token type)")
    void exchange_rejectsAccessToken() throws Exception {
        mockMvc.perform(post("/auth/handoff/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("ticket", token))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("auth.handoff_token_invalid"));
    }
}
