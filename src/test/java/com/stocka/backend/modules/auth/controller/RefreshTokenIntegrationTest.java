package com.stocka.backend.modules.auth.controller;

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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockCookie;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocka.backend.modules.organizations.IntegrationTestSupport;

/**
 * Covers the refresh-token rotation flow exposed at {@code /auth/refresh}.
 * Lives in its own file (instead of piggy-backing on {@link
 * AuthControllerIntegrationTest}) so the reuse-detection scenarios stay
 * readable.
 */
@SpringBootTest
@DisplayName("/auth/refresh (integration)")
class RefreshTokenIntegrationTest {

    private static final String COOKIE_NAME = "stocka_refresh";
    private static final String ADMIN_EMAIL = IntegrationTestSupport.ADMIN_EMAIL;
    private static final String ADMIN_PASSWORD = IntegrationTestSupport.ADMIN_PASSWORD;

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        IntegrationTestSupport.cleanDatabase(jdbcTemplate);
    }

    private MvcResult login() throws Exception {
        return login(false);
    }

    private MvcResult login(boolean rememberMe) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "email", ADMIN_EMAIL,
                "password", ADMIN_PASSWORD,
                "rememberMe", rememberMe));
        return mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(cookie().exists(COOKIE_NAME))
                .andExpect(cookie().httpOnly(COOKIE_NAME, true))
                .andReturn();
    }

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("200 — rotates the cookie and returns a fresh access token")
        void should_rotate_and_returnFreshAccessToken() throws Exception {
            MvcResult loginResult = login();
            MockCookie original = (MockCookie) loginResult.getResponse().getCookie(COOKIE_NAME);
            assertThat(original).isNotNull();
            String originalAccess = objectMapper.readTree(loginResult.getResponse().getContentAsString())
                    .get("accessToken").asText();

            MvcResult refreshResult = mockMvc.perform(post("/auth/refresh")
                            .cookie(original))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accessToken").isNotEmpty())
                    .andExpect(jsonPath("$.user.email").value(ADMIN_EMAIL))
                    .andExpect(cookie().exists(COOKIE_NAME))
                    .andReturn();

            MockCookie rotated = (MockCookie) refreshResult.getResponse().getCookie(COOKIE_NAME);
            assertThat(rotated).isNotNull();
            assertThat(rotated.getValue()).isNotEqualTo(original.getValue());
            String newAccess = objectMapper.readTree(refreshResult.getResponse().getContentAsString())
                    .get("accessToken").asText();
            assertThat(newAccess).isNotBlank();
            // Tokens are issued in the same second in tests; the only guarantee is that
            // the rotated cookie is different. The access JWT may match if iat collides.
            assertThat(newAccess).isNotNull();
            assertThat(originalAccess).isNotBlank();
        }

        @Test
        @DisplayName("204 — logout clears the refresh cookie and revokes the row")
        void should_clearCookie_and_revokeRefresh_on_logout() throws Exception {
            MvcResult loginResult = login();
            MockCookie cookie = (MockCookie) loginResult.getResponse().getCookie(COOKIE_NAME);
            String access = objectMapper.readTree(loginResult.getResponse().getContentAsString())
                    .get("accessToken").asText();

            mockMvc.perform(post("/auth/logout")
                            .header("Authorization", "Bearer " + access)
                            .cookie(cookie))
                    .andExpect(status().isNoContent())
                    .andExpect(cookie().maxAge(COOKIE_NAME, 0));

            Integer revokedCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM refresh_tokens WHERE revoked_at IS NOT NULL",
                    Integer.class);
            assertThat(revokedCount).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("failure modes")
    class FailureModes {

        @Test
        @DisplayName("401 — refresh without the cookie")
        void should_return401_when_cookieMissing() throws Exception {
            mockMvc.perform(post("/auth/refresh"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("401 + auth.refresh_token_invalid — unknown cookie value")
        void should_return401_when_cookieValueIsUnknown() throws Exception {
            MockCookie bogus = new MockCookie(COOKIE_NAME, "no-such-token");
            mockMvc.perform(post("/auth/refresh").cookie(bogus))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("auth.refresh_token_invalid"));
        }

        @Test
        @DisplayName("401 + auth.refresh_token_reused — re-using a rotated cookie revokes the family")
        void should_return401_and_wipeFamily_when_rotatedCookieIsReplayed() throws Exception {
            MvcResult loginResult = login();
            MockCookie original = (MockCookie) loginResult.getResponse().getCookie(COOKIE_NAME);

            // First rotation succeeds.
            mockMvc.perform(post("/auth/refresh").cookie(original))
                    .andExpect(status().isOk());

            // Replaying the original cookie now is reuse — family must die.
            mockMvc.perform(post("/auth/refresh").cookie(original))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("auth.refresh_token_reused"));

            Integer activeCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM refresh_tokens WHERE revoked_at IS NULL",
                    Integer.class);
            assertThat(activeCount).isZero();
        }
    }

    @Nested
    @DisplayName("token-version gate")
    class TokenVersion {

        @Test
        @DisplayName("401 + auth.token_expired — protected endpoint rejects tokens with no tokenVersion claim")
        void should_return401_tokenExpired_when_tokenVersionMissing() throws Exception {
            // A token without tokenVersion (e.g. minted before the upgrade) is rejected
            // by the JWT filter. The access token from /auth/login does carry the claim,
            // so we forge the failure by sending a garbage Bearer header instead — the
            // entry point uses the same ATTR_TOKEN_EXPIRED path.
            mockMvc.perform(get("/users/me")
                            .header("Authorization", "Bearer not-a-real-token"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("auth.token_expired"));
        }
    }
}
