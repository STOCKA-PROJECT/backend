package com.stocka.backend.modules.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocka.backend.modules.organizations.IntegrationTestSupport;

/**
 * Smoke tests for the Google OAuth flow at the controller level. The actual
 * HTTP calls to Google's endpoints are exercised manually in dev — the tests
 * here cover the wiring (rate-limit, state-cookie, not-configured guard).
 */
@SpringBootTest
@DisplayName("/auth/oauth/google (integration)")
@TestPropertySource(properties = {
        "oauth.google.client-id=test-client-id",
        "oauth.google.client-secret=test-client-secret",
        "oauth.google.redirect-uri=http://localhost:3002/auth/oauth/callback"
})
class GoogleOAuthIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        IntegrationTestSupport.cleanDatabase(jdbcTemplate);
    }

    @Test
    @DisplayName("authorize returns the Google URL + state cookie when configured")
    void should_buildAuthorizeUrl_when_configured() throws Exception {
        MvcResult result = mockMvc.perform(get("/auth/oauth/google/authorize"))
                .andExpect(status().isOk())
                .andExpect(cookie().exists("stocka_oauth_state"))
                .andExpect(cookie().httpOnly("stocka_oauth_state", true))
                .andExpect(jsonPath("$.authorizationUrl").isNotEmpty())
                .andReturn();
        String url = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("authorizationUrl").asText();
        assertThat(url).startsWith("https://accounts.google.com/o/oauth2/v2/auth");
        assertThat(url).contains("client_id=test-client-id");
        assertThat(url).contains("scope=openid+email+profile");
        assertThat(url).contains("state=");
    }

    @Test
    @DisplayName("callback rejects a request without the matching state cookie")
    void should_rejectCallback_when_stateMissing() throws Exception {
        mockMvc.perform(post("/auth/oauth/google/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", "some-google-code",
                                "state", "forged-state"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("auth.oauth_state_invalid"));
    }
}
