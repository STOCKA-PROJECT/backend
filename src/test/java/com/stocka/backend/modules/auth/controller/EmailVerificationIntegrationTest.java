package com.stocka.backend.modules.auth.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import java.util.HashMap;
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

@SpringBootTest
@DisplayName("Email verification endpoints (integration)")
class EmailVerificationIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Value("${app.email.local.dir:target/emails}")
    private String emailLocalDir;

    private final ObjectMapper om = new ObjectMapper();
    private MockMvc mockMvc;

    private static final String EMAIL = "verify-user@test.com";
    private static final String USERNAME = "verifyuser";
    private static final String PASSWORD = "password123";

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

    private void signupRaw(String email, String username, String language) throws Exception {
        Map<String, String> body = new HashMap<>();
        body.put("name", "Test");
        body.put("lastName", "User");
        body.put("username", username);
        body.put("email", email);
        body.put("password", PASSWORD);
        body.put("repeatPassword", PASSWORD);
        if (language != null) body.put("language", language);
        mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isOk());
    }

    private void signupRaw(String email, String username) throws Exception {
        signupRaw(email, username, null);
    }

    private static String sha256Hex(String raw) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(md.digest(raw.getBytes(StandardCharsets.UTF_8)));
    }

    private String readLatestVerificationFile() throws IOException {
        File dir = new File(emailLocalDir);
        File[] files = dir.listFiles((d, name) -> name.startsWith("email-verification-") && name.endsWith(".html"));
        assertNotNull(files, "no email files written to " + emailLocalDir);
        assertTrue(files.length > 0, "expected at least one email-verification email");
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

    private int countVerificationFiles() {
        File dir = new File(emailLocalDir);
        if (!dir.exists()) return 0;
        File[] files = dir.listFiles((d, name) -> name.startsWith("email-verification-") && name.endsWith(".html"));
        return files == null ? 0 : files.length;
    }

    private int countTokensFor(String email) {
        Integer userId = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE email = ?", Integer.class, email);
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM email_verification_tokens WHERE user_id = ?", Integer.class, userId);
    }

    // -------------------------------------------------------------------------
    // Signup → verification email is sent
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Signup → verification email")
    class PostSignupVerification {

        @Test
        @DisplayName("signup writes an email-verification file in target/emails")
        void should_writeEmailVerificationFile_when_signupSucceeds() throws Exception {
            signupRaw(EMAIL, USERNAME);

            assertEquals(1, countVerificationFiles());
        }

        @Test
        @DisplayName("signup leaves the user as unverified in DB")
        void should_storeUserAsUnverified_when_signupSucceeds() throws Exception {
            signupRaw(EMAIL, USERNAME);

            Boolean verified = jdbcTemplate.queryForObject(
                    "SELECT email_verified FROM users WHERE email = ?",
                    Boolean.class, EMAIL);
            assertEquals(Boolean.FALSE, verified);
        }

        @Test
        @DisplayName("persisted token is hashed (raw token does NOT match any stored hash)")
        void should_storeHashedToken() throws Exception {
            signupRaw(EMAIL, USERNAME);
            String raw = extractToken(readLatestVerificationFile());

            Integer rawMatches = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM email_verification_tokens WHERE token_hash = ?",
                    Integer.class, raw);
            assertEquals(0, rawMatches, "raw token must NOT match any stored hash");
            Integer hashedMatches = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM email_verification_tokens WHERE token_hash = ?",
                    Integer.class, sha256Hex(raw));
            assertEquals(1, hashedMatches);
        }

        @Test
        @DisplayName("ES (default): email body is in Spanish")
        void should_writeSpanishEmail_when_userLanguageIsEs() throws Exception {
            signupRaw(EMAIL, USERNAME);

            String content = readLatestVerificationFile();
            assertTrue(content.contains("Verificar correo"),
                    "ES body should contain 'Verificar correo': " + content);
            assertTrue(content.contains("Verifica tu cuenta en Stocka"),
                    "ES subject should be embedded as a comment: " + content);
        }

        @Test
        @DisplayName("EN: email body is in English")
        void should_writeEnglishEmail_when_userLanguageIsEn() throws Exception {
            signupRaw(EMAIL, USERNAME, "EN");

            String content = readLatestVerificationFile();
            assertTrue(content.contains("Verify email"),
                    "EN body should contain 'Verify email': " + content);
            assertTrue(content.contains("Verify your Stocka account"),
                    "EN subject should be embedded as a comment: " + content);
        }

        @Test
        @DisplayName("CA: email body is in Catalan")
        void should_writeCatalanEmail_when_userLanguageIsCa() throws Exception {
            signupRaw(EMAIL, USERNAME, "CA");

            String content = readLatestVerificationFile();
            assertTrue(content.contains("Verificar correu"),
                    "CA body should contain 'Verificar correu': " + content);
            assertTrue(content.contains("Verifica el teu compte a Stocka"),
                    "CA subject should be embedded as a comment: " + content);
        }
    }

    // -------------------------------------------------------------------------
    // POST /auth/verify-email
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("POST /auth/verify-email")
    class PostVerifyEmail {

        @Test
        @DisplayName("204 — should mark the user as verified when token is valid")
        void should_return204_andMarkUserVerified_when_validToken() throws Exception {
            signupRaw(EMAIL, USERNAME);
            String raw = extractToken(readLatestVerificationFile());

            mockMvc.perform(post("/auth/verify-email")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of("token", raw))))
                    .andExpect(status().isNoContent());

            Boolean verified = jdbcTemplate.queryForObject(
                    "SELECT email_verified FROM users WHERE email = ?",
                    Boolean.class, EMAIL);
            assertEquals(Boolean.TRUE, verified);
        }

        @Test
        @DisplayName("login is blocked before verification and succeeds after")
        void should_allowLogin_after_verification() throws Exception {
            signupRaw(EMAIL, USERNAME);

            mockMvc.perform(post("/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of("email", EMAIL, "password", PASSWORD))))
                    .andExpect(status().isForbidden());

            String raw = extractToken(readLatestVerificationFile());
            mockMvc.perform(post("/auth/verify-email")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of("token", raw))))
                    .andExpect(status().isNoContent());

            mockMvc.perform(post("/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of("email", EMAIL, "password", PASSWORD))))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("400 — should reject when token is unknown")
        void should_return400_when_tokenUnknown() throws Exception {
            mockMvc.perform(post("/auth/verify-email")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of("token", "totally-fake-token"))))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("400 — should reject when token is reused")
        void should_return400_when_tokenReused() throws Exception {
            signupRaw(EMAIL, USERNAME);
            String raw = extractToken(readLatestVerificationFile());

            mockMvc.perform(post("/auth/verify-email")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of("token", raw))))
                    .andExpect(status().isNoContent());

            mockMvc.perform(post("/auth/verify-email")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of("token", raw))))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("400 — should reject when token has expired")
        void should_return400_when_tokenExpired() throws Exception {
            signupRaw(EMAIL, USERNAME);
            String raw = extractToken(readLatestVerificationFile());
            jdbcTemplate.update(
                    "UPDATE email_verification_tokens SET expires_at = ? WHERE token_hash = ?",
                    LocalDateTime.now().minusMinutes(1), sha256Hex(raw));

            mockMvc.perform(post("/auth/verify-email")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of("token", raw))))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("400 — should reject when token field is missing")
        void should_return400_when_tokenMissing() throws Exception {
            mockMvc.perform(post("/auth/verify-email")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("after verification, the stored token has a non-null used_at")
        void should_markTokenUsed_inDb() throws Exception {
            signupRaw(EMAIL, USERNAME);
            String raw = extractToken(readLatestVerificationFile());

            mockMvc.perform(post("/auth/verify-email")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of("token", raw))))
                    .andExpect(status().isNoContent());

            Integer used = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM email_verification_tokens WHERE token_hash = ? AND used_at IS NOT NULL",
                    Integer.class, sha256Hex(raw));
            assertEquals(1, used);
        }
    }

    // -------------------------------------------------------------------------
    // POST /auth/resend-verification
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("POST /auth/resend-verification")
    class PostResendVerification {

        @Test
        @DisplayName("204 — should re-send for an existing unverified user (new token replaces the previous one)")
        void should_return204_for_existingUnverifiedUser() throws Exception {
            signupRaw(EMAIL, USERNAME);
            String firstHash = jdbcTemplate.queryForObject(
                    "SELECT token_hash FROM email_verification_tokens "
                            + "WHERE user_id = (SELECT id FROM users WHERE email = ?)",
                    String.class, EMAIL);

            mockMvc.perform(post("/auth/resend-verification")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of("email", EMAIL))))
                    .andExpect(status().isNoContent());

            assertEquals(1, countTokensFor(EMAIL), "previous token should be invalidated");
            String secondHash = jdbcTemplate.queryForObject(
                    "SELECT token_hash FROM email_verification_tokens "
                            + "WHERE user_id = (SELECT id FROM users WHERE email = ?)",
                    String.class, EMAIL);
            assertTrue(!secondHash.equals(firstHash), "the resend should produce a new token hash");
        }

        @Test
        @DisplayName("204 — silent for an unknown email (anti-enumeration)")
        void should_return204_for_unknownUser() throws Exception {
            mockMvc.perform(post("/auth/resend-verification")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of("email", "ghost@test.com"))))
                    .andExpect(status().isNoContent());

            assertEquals(0, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM email_verification_tokens", Integer.class));
            assertEquals(0, countVerificationFiles());
        }

        @Test
        @DisplayName("204 — silent for an already-verified user (anti-enumeration)")
        void should_return204_for_alreadyVerifiedUser() throws Exception {
            signupRaw(EMAIL, USERNAME);
            // simulate the user having verified
            jdbcTemplate.update("UPDATE users SET email_verified = TRUE WHERE email = ?", EMAIL);
            jdbcTemplate.update("DELETE FROM email_verification_tokens");
            int initialFiles = countVerificationFiles();

            mockMvc.perform(post("/auth/resend-verification")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of("email", EMAIL))))
                    .andExpect(status().isNoContent());

            assertEquals(0, countTokensFor(EMAIL), "no new token should be persisted");
            assertEquals(initialFiles, countVerificationFiles(), "no new email file should appear");
        }

        @Test
        @DisplayName("204 — silent when email is missing or blank")
        void should_return204_when_emailMissingOrBlank() throws Exception {
            mockMvc.perform(post("/auth/resend-verification")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isNoContent());

            mockMvc.perform(post("/auth/resend-verification")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of("email", "   "))))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("204 — second resend invalidates the previous token (only one usable token at a time)")
        void should_invalidatePreviousToken_when_resendCalled() throws Exception {
            signupRaw(EMAIL, USERNAME);
            mockMvc.perform(post("/auth/resend-verification")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of("email", EMAIL))))
                    .andExpect(status().isNoContent());

            assertEquals(1, countTokensFor(EMAIL));
        }
    }
}
