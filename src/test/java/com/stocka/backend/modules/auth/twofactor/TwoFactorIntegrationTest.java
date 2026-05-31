package com.stocka.backend.modules.auth.twofactor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocka.backend.modules.organizations.IntegrationTestSupport;

/**
 * End-to-end tests for the 2FA flow (Feature 2). Walks through setup,
 * confirmation, login challenge and disable, exercising both the TOTP and
 * recovery-code paths against the real Spring context.
 */
@SpringBootTest
@DisplayName("Two-factor authentication (integration)")
class TwoFactorIntegrationTest {

    private static final String ADMIN_EMAIL = IntegrationTestSupport.ADMIN_EMAIL;
    private static final String ADMIN_PASSWORD = IntegrationTestSupport.ADMIN_PASSWORD;

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TotpGenerator totpGenerator;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        IntegrationTestSupport.cleanDatabase(jdbcTemplate);
    }

    private String loginAndGetToken() throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", ADMIN_EMAIL,
                                "password", ADMIN_PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("accessToken").asText();
    }

    @Test
    @DisplayName("setup → confirm → login challenge succeeds with the right TOTP code")
    void should_completeFullFlow_with_validTotp() throws Exception {
        String token = loginAndGetToken();

        MvcResult setupResult = mockMvc.perform(post("/auth/2fa/setup")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.setupToken").isNotEmpty())
                .andExpect(jsonPath("$.secret").isNotEmpty())
                .andExpect(jsonPath("$.otpAuthUri").value(org.hamcrest.Matchers.startsWith("otpauth://totp/")))
                .andReturn();
        JsonNode setup = objectMapper.readTree(setupResult.getResponse().getContentAsString());
        String secret = setup.get("secret").asText();
        String setupToken = setup.get("setupToken").asText();

        mockMvc.perform(post("/auth/2fa/confirm")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "setupToken", setupToken,
                                "code", totpGenerator.currentCode(secret)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recoveryCodes").isArray())
                .andExpect(jsonPath("$.recoveryCodes.length()").value(10));

        Integer enabled = jdbcTemplate.queryForObject(
                "SELECT two_factor_enabled FROM users WHERE email = ?",
                Integer.class, ADMIN_EMAIL);
        assertThat(enabled).isEqualTo(1);

        MvcResult loginResult = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", ADMIN_EMAIL,
                                "password", ADMIN_PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requires2fa").value(true))
                .andExpect(jsonPath("$.mfaToken").isNotEmpty())
                .andReturn();
        String mfaToken = objectMapper.readTree(loginResult.getResponse().getContentAsString())
                .get("mfaToken").asText();

        mockMvc.perform(post("/auth/login/2fa")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "mfaToken", mfaToken,
                                "code", totpGenerator.currentCode(secret)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    @Test
    @DisplayName("login/2fa rejects an invalid code with auth.2fa_invalid_code")
    void should_rejectInvalidCode_on_challenge() throws Exception {
        // Enable 2FA via the happy path first
        String token = loginAndGetToken();
        MvcResult setupResult = mockMvc.perform(post("/auth/2fa/setup")
                        .header("Authorization", "Bearer " + token))
                .andReturn();
        JsonNode setup = objectMapper.readTree(setupResult.getResponse().getContentAsString());
        String secret = setup.get("secret").asText();
        mockMvc.perform(post("/auth/2fa/confirm")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "setupToken", setup.get("setupToken").asText(),
                                "code", totpGenerator.currentCode(secret)))))
                .andExpect(status().isOk());

        MvcResult loginResult = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", ADMIN_EMAIL,
                                "password", ADMIN_PASSWORD))))
                .andReturn();
        String mfaToken = objectMapper.readTree(loginResult.getResponse().getContentAsString())
                .get("mfaToken").asText();

        mockMvc.perform(post("/auth/login/2fa")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "mfaToken", mfaToken,
                                "code", "000000"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("auth.2fa_invalid_code"));
    }

    @Test
    @DisplayName("recovery code unlocks the challenge and is consumed on use")
    void should_acceptRecoveryCode_and_burnIt() throws Exception {
        String token = loginAndGetToken();
        MvcResult setupResult = mockMvc.perform(post("/auth/2fa/setup")
                        .header("Authorization", "Bearer " + token))
                .andReturn();
        JsonNode setup = objectMapper.readTree(setupResult.getResponse().getContentAsString());
        String secret = setup.get("secret").asText();
        MvcResult confirmResult = mockMvc.perform(post("/auth/2fa/confirm")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "setupToken", setup.get("setupToken").asText(),
                                "code", totpGenerator.currentCode(secret)))))
                .andReturn();
        String recoveryCode = objectMapper.readTree(confirmResult.getResponse().getContentAsString())
                .get("recoveryCodes").get(0).asText();

        MvcResult loginResult = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", ADMIN_EMAIL,
                                "password", ADMIN_PASSWORD))))
                .andReturn();
        String mfaToken = objectMapper.readTree(loginResult.getResponse().getContentAsString())
                .get("mfaToken").asText();

        mockMvc.perform(post("/auth/login/2fa")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "mfaToken", mfaToken,
                                "code", recoveryCode))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());

        // Same recovery code can't be reused.
        MvcResult loginResult2 = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", ADMIN_EMAIL,
                                "password", ADMIN_PASSWORD))))
                .andReturn();
        String mfaToken2 = objectMapper.readTree(loginResult2.getResponse().getContentAsString())
                .get("mfaToken").asText();
        mockMvc.perform(post("/auth/login/2fa")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "mfaToken", mfaToken2,
                                "code", recoveryCode))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("disable requires both the current password AND a valid code")
    void should_requireDualFactor_on_disable() throws Exception {
        String token = loginAndGetToken();
        MvcResult setupResult = mockMvc.perform(post("/auth/2fa/setup")
                        .header("Authorization", "Bearer " + token))
                .andReturn();
        JsonNode setup = objectMapper.readTree(setupResult.getResponse().getContentAsString());
        String secret = setup.get("secret").asText();
        mockMvc.perform(post("/auth/2fa/confirm")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "setupToken", setup.get("setupToken").asText(),
                                "code", totpGenerator.currentCode(secret)))))
                .andExpect(status().isOk());

        // Wrong password → 401
        mockMvc.perform(post("/auth/2fa/disable")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "currentPassword", "wrong",
                                "code", totpGenerator.currentCode(secret)))))
                .andExpect(status().isUnauthorized());

        // Right password, wrong code → 401
        mockMvc.perform(post("/auth/2fa/disable")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "currentPassword", ADMIN_PASSWORD,
                                "code", "000000"))))
                .andExpect(status().isUnauthorized());

        // Both right → 204, 2FA disabled
        mockMvc.perform(post("/auth/2fa/disable")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "currentPassword", ADMIN_PASSWORD,
                                "code", totpGenerator.currentCode(secret)))))
                .andExpect(status().isNoContent());

        Integer enabled = jdbcTemplate.queryForObject(
                "SELECT two_factor_enabled FROM users WHERE email = ?",
                Integer.class, ADMIN_EMAIL);
        assertThat(enabled).isEqualTo(0);
    }

    /**
     * Regression for the 401-vs-403 mismatch: the 2FA management endpoints live
     * under the {@code /auth/**} prefix, which is {@code permitAll()} at the URL
     * layer. They must still reject anonymous callers with <strong>401</strong>
     * (via the authentication entry point) rather than a 403 from the
     * method-level {@code @PreAuthorize} — the frontend only triggers a silent
     * token refresh on 401, so a 403 would leave an expired session stuck.
     */
    @org.junit.jupiter.api.Nested
    @DisplayName("Unauthenticated access → 401 (not 403)")
    class UnauthenticatedAccess {

        @Test
        @DisplayName("POST /auth/2fa/setup without a token returns 401")
        void should_return401_when_setupWithoutToken() throws Exception {
            mockMvc.perform(post("/auth/2fa/setup"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("POST /auth/2fa/confirm without a token returns 401")
        void should_return401_when_confirmWithoutToken() throws Exception {
            mockMvc.perform(post("/auth/2fa/confirm")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "setupToken", "x", "code", "000000"))))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("POST /auth/2fa/disable without a token returns 401")
        void should_return401_when_disableWithoutToken() throws Exception {
            mockMvc.perform(post("/auth/2fa/disable")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "currentPassword", "x", "code", "000000"))))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("POST /auth/2fa/recovery-codes/regenerate without a token returns 401")
        void should_return401_when_regenerateWithoutToken() throws Exception {
            mockMvc.perform(post("/auth/2fa/recovery-codes/regenerate"))
                    .andExpect(status().isUnauthorized());
        }
    }
}
