package com.stocka.backend.modules.auth.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocka.backend.modules.organizations.IntegrationTestSupport;
import com.stocka.backend.modules.users.entity.Language;
import com.stocka.backend.modules.users.entity.User;
import com.stocka.backend.modules.users.repository.UserRepository;

@SpringBootTest
@DisplayName("Password reset endpoints (integration)")
class PasswordResetIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Value("${app.email.local.dir:target/emails}")
    private String emailLocalDir;

    private final ObjectMapper om = new ObjectMapper();
    private MockMvc mockMvc;

    private static final String EMAIL = "reset-user@test.com";
    private static final String USERNAME = "resetuser";
    private static final String OLD_PASSWORD = "password123";
    private static final String NEW_PASSWORD = "newPassword456";

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = IntegrationTestSupport.buildMockMvc(context);
        IntegrationTestSupport.cleanDatabase(jdbcTemplate);
        clearLocalEmailDir();
    }

    private void clearLocalEmailDir() throws IOException {
        File dir = new File(emailLocalDir);
        if (!dir.exists()) {
            return;
        }
        try (var stream = Files.walk(dir.toPath())) {
            stream.sorted(Comparator.reverseOrder())
                    .filter(p -> !p.equals(dir.toPath()))
                    .forEach(p -> p.toFile().delete());
        }
    }

    private void signupUser() throws Exception {
        IntegrationTestSupport.signupAndLogin(mockMvc, om, jdbcTemplate, EMAIL, USERNAME);
    }

    private static String sha256Hex(String raw) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(md.digest(raw.getBytes(StandardCharsets.UTF_8)));
    }

    private String triggerResetAndExtractRawToken() throws Exception {
        mockMvc.perform(post("/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("email", EMAIL))))
                .andExpect(status().isNoContent());
        return readRawTokenFromEmail();
    }

    private String readRawTokenFromEmail() throws IOException {
        return extractToken(readLatestPasswordResetFile());
    }

    private String readLatestPasswordResetFile() throws IOException {
        File dir = new File(emailLocalDir);
        File[] files = dir.listFiles((d, name) -> name.startsWith("password-reset-") && name.endsWith(".html"));
        assertNotNull(files, "no email files written to " + emailLocalDir);
        assertTrue(files.length > 0, "expected at least one password-reset email");
        File latest = files[0];
        for (File f : files) {
            if (f.lastModified() > latest.lastModified()) latest = f;
        }
        return Files.readString(Path.of(latest.getAbsolutePath()));
    }

    private static String extractToken(String emailContent) {
        Matcher m = Pattern.compile("token=([A-Za-z0-9_-]+)").matcher(emailContent);
        assertTrue(m.find(), "no token found in email body: " + emailContent);
        return m.group(1);
    }

    // -------------------------------------------------------------------------
    // POST /auth/forgot-password
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("POST /auth/forgot-password")
    class ForgotPassword {

        @Test
        @DisplayName("204 — should respond no content for an existing user")
        void should_return204_for_existingUser() throws Exception {
            signupUser();

            mockMvc.perform(post("/auth/forgot-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of("email", EMAIL))))
                    .andExpect(status().isNoContent());

            assertEquals(1, countTokensFor(EMAIL));
        }

        @Test
        @DisplayName("204 — should respond no content for a non-existent user (anti-enumeration)")
        void should_return204_for_unknownUser() throws Exception {
            mockMvc.perform(post("/auth/forgot-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of("email", "ghost@test.com"))))
                    .andExpect(status().isNoContent());

            assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM password_reset_tokens", Integer.class));
        }

        @Test
        @DisplayName("204 — should respond no content even when email is missing from the body")
        void should_return204_when_emailMissing() throws Exception {
            mockMvc.perform(post("/auth/forgot-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("204 — should respond no content when email is empty (anti-enumeration)")
        void should_return204_when_emailEmpty() throws Exception {
            mockMvc.perform(post("/auth/forgot-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of("email", ""))))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("400 — should reject malformed (non-email) input")
        void should_return400_when_emailMalformed() throws Exception {
            mockMvc.perform(post("/auth/forgot-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of("email", "not-an-email"))))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("204 — second request invalidates the previous token (only one usable token at a time)")
        void should_invalidatePreviousToken_when_requestedAgain() throws Exception {
            signupUser();
            triggerResetAndExtractRawToken();
            String secondRawToken = triggerResetAndExtractRawToken();

            assertEquals(1, countTokensFor(EMAIL));
            assertEquals(1, countTokensWithHash(secondRawToken));
        }

        @Test
        @DisplayName("persisted token is hashed (not stored in raw form)")
        void should_storeHashedToken() throws Exception {
            signupUser();
            String raw = triggerResetAndExtractRawToken();

            Integer rawMatches = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM password_reset_tokens WHERE token_hash = ?",
                    Integer.class, raw);
            assertEquals(0, rawMatches, "raw token must NOT match any stored hash");
            assertEquals(1, countTokensWithHash(raw));
        }

        @Test
        @DisplayName("when user.language=EN, the written email file is in English")
        void should_writeEnglishEmail_when_userLanguageIsEn() throws Exception {
            signupUser();
            // flip the user's language directly via repo
            User user = userRepository.findByEmail(EMAIL).orElseThrow();
            userRepository.save(user.setLanguage(Language.EN));

            triggerResetAndExtractRawToken();

            String content = readLatestPasswordResetFile();
            assertTrue(content.contains("Reset password"),
                    "EN body should contain 'Reset password': " + content);
            assertTrue(content.contains("Reset your Stocka password"),
                    "EN subject should be embedded as a comment: " + content);
            assertTrue(!content.contains("Restablecer contraseña"),
                    "should NOT contain Spanish CTA when user is EN");
        }

        @Test
        @DisplayName("when user.language=CA, the written email file is in Catalan")
        void should_writeCatalanEmail_when_userLanguageIsCa() throws Exception {
            signupUser();
            User user = userRepository.findByEmail(EMAIL).orElseThrow();
            userRepository.save(user.setLanguage(Language.CA));

            triggerResetAndExtractRawToken();

            String content = readLatestPasswordResetFile();
            assertTrue(content.contains("Restablir contrasenya"),
                    "CA body should contain 'Restablir contrasenya': " + content);
            assertTrue(content.contains("Restableix la teva contrasenya a Stocka"),
                    "CA subject should be embedded as a comment: " + content);
        }

        @Test
        @DisplayName("when user.language=ES (default), the written email file is in Spanish")
        void should_writeSpanishEmail_when_userLanguageIsEs() throws Exception {
            signupUser();

            triggerResetAndExtractRawToken();

            String content = readLatestPasswordResetFile();
            assertTrue(content.contains("Restablecer contraseña"),
                    "ES body should contain 'Restablecer contraseña': " + content);
        }
    }

    // -------------------------------------------------------------------------
    // POST /auth/reset-password
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("POST /auth/reset-password")
    class ResetPassword {

        @Test
        @DisplayName("204 — should reset password and allow login with the new password")
        void should_reset_and_allowLoginWithNewPassword() throws Exception {
            signupUser();
            String raw = triggerResetAndExtractRawToken();

            mockMvc.perform(post("/auth/reset-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of(
                                    "token", raw,
                                    "newPassword", NEW_PASSWORD,
                                    "repeatPassword", NEW_PASSWORD
                            ))))
                    .andExpect(status().isNoContent());

            mockMvc.perform(post("/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of("email", EMAIL, "password", NEW_PASSWORD))))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("401 — old password no longer works after reset")
        void should_rejectOldPassword_after_reset() throws Exception {
            signupUser();
            String raw = triggerResetAndExtractRawToken();
            mockMvc.perform(post("/auth/reset-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of(
                                    "token", raw,
                                    "newPassword", NEW_PASSWORD,
                                    "repeatPassword", NEW_PASSWORD
                            ))))
                    .andExpect(status().isNoContent());

            mockMvc.perform(post("/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of("email", EMAIL, "password", OLD_PASSWORD))))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("400 — should reject reuse of the same token")
        void should_return400_when_tokenReused() throws Exception {
            signupUser();
            String raw = triggerResetAndExtractRawToken();

            mockMvc.perform(post("/auth/reset-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of(
                                    "token", raw,
                                    "newPassword", NEW_PASSWORD,
                                    "repeatPassword", NEW_PASSWORD
                            ))))
                    .andExpect(status().isNoContent());

            mockMvc.perform(post("/auth/reset-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of(
                                    "token", raw,
                                    "newPassword", "anotherPassword999",
                                    "repeatPassword", "anotherPassword999"
                            ))))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("400 — should reject when token is unknown")
        void should_return400_when_tokenUnknown() throws Exception {
            mockMvc.perform(post("/auth/reset-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of(
                                    "token", "totally-fake-token",
                                    "newPassword", NEW_PASSWORD,
                                    "repeatPassword", NEW_PASSWORD
                            ))))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("400 — should reject when token is expired")
        void should_return400_when_tokenExpired() throws Exception {
            signupUser();
            String raw = triggerResetAndExtractRawToken();
            jdbcTemplate.update(
                    "UPDATE password_reset_tokens SET expires_at = ? WHERE token_hash = ?",
                    LocalDateTime.now().minusMinutes(1), sha256Hex(raw)
            );

            mockMvc.perform(post("/auth/reset-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of(
                                    "token", raw,
                                    "newPassword", NEW_PASSWORD,
                                    "repeatPassword", NEW_PASSWORD
                            ))))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("400 — should reject when newPassword and repeatPassword differ")
        void should_return400_when_passwordsDoNotMatch() throws Exception {
            signupUser();
            String raw = triggerResetAndExtractRawToken();

            mockMvc.perform(post("/auth/reset-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of(
                                    "token", raw,
                                    "newPassword", NEW_PASSWORD,
                                    "repeatPassword", "different"
                            ))))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("400 — should reject when password is shorter than 8 characters")
        void should_return400_when_passwordTooShort() throws Exception {
            signupUser();
            String raw = triggerResetAndExtractRawToken();

            mockMvc.perform(post("/auth/reset-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of(
                                    "token", raw,
                                    "newPassword", "short",
                                    "repeatPassword", "short"
                            ))))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("400 — should reject when token field is missing")
        void should_return400_when_tokenMissing() throws Exception {
            mockMvc.perform(post("/auth/reset-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of(
                                    "newPassword", NEW_PASSWORD,
                                    "repeatPassword", NEW_PASSWORD
                            ))))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("after reset, JWTs issued before the reset are rejected (passwordChangedAt > iat)")
        void should_invalidatePreExistingJwts_after_reset() throws Exception {
            signupUser();
            String tokenBeforeReset = IntegrationTestSupport.login(mockMvc, om, EMAIL, OLD_PASSWORD);
            mockMvc.perform(get("/users/me").header("Authorization", "Bearer " + tokenBeforeReset))
                    .andExpect(status().isOk());

            String raw = triggerResetAndExtractRawToken();
            // ensure passwordChangedAt is strictly after the iat second of the JWT (JWT iat granularity = 1 sec)
            Thread.sleep(1100);
            mockMvc.perform(post("/auth/reset-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of(
                                    "token", raw,
                                    "newPassword", NEW_PASSWORD,
                                    "repeatPassword", NEW_PASSWORD
                            ))))
                    .andExpect(status().isNoContent());

            mockMvc.perform(get("/users/me").header("Authorization", "Bearer " + tokenBeforeReset))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("after reset, the user's passwordChangedAt is set in the database")
        void should_setPasswordChangedAtInDb_after_reset() throws Exception {
            signupUser();
            String raw = triggerResetAndExtractRawToken();

            mockMvc.perform(post("/auth/reset-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of(
                                    "token", raw,
                                    "newPassword", NEW_PASSWORD,
                                    "repeatPassword", NEW_PASSWORD
                            ))))
                    .andExpect(status().isNoContent());

            User refreshed = userRepository.findByEmail(EMAIL).orElseThrow();
            assertNotNull(refreshed.getPasswordChangedAt());
        }

        @Test
        @DisplayName("after reset, the stored token has a non-null usedAt")
        void should_markTokenUsed_inDb_after_reset() throws Exception {
            signupUser();
            String raw = triggerResetAndExtractRawToken();

            mockMvc.perform(post("/auth/reset-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of(
                                    "token", raw,
                                    "newPassword", NEW_PASSWORD,
                                    "repeatPassword", NEW_PASSWORD
                            ))))
                    .andExpect(status().isNoContent());

            Integer used = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM password_reset_tokens WHERE token_hash = ? AND used_at IS NOT NULL",
                    Integer.class, sha256Hex(raw));
            assertEquals(1, used);
        }
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    private int countTokensFor(String email) {
        Integer userId = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE email = ?", Integer.class, email);
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM password_reset_tokens WHERE user_id = ?", Integer.class, userId);
    }

    private int countTokensWithHash(String rawToken) throws Exception {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM password_reset_tokens WHERE token_hash = ?",
                Integer.class, sha256Hex(rawToken));
    }
}
