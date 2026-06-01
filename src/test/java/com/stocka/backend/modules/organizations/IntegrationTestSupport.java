package com.stocka.backend.modules.organizations;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.fasterxml.jackson.databind.ObjectMapper;

public final class IntegrationTestSupport {

    public static final String ADMIN_EMAIL = "joanmartorellcoll03@gmail.com";
    public static final String ADMIN_PASSWORD = "12345678";

    private IntegrationTestSupport() {}

    public static MockMvc buildMockMvc(WebApplicationContext context) {
        return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    /**
     * H2-specific cleanup that bypasses {@code @SQLRestriction} (which only filters SELECT).
     * Uses {@code SET REFERENTIAL_INTEGRITY FALSE} to wipe tables in any order.
     */
    public static void cleanDatabase(JdbcTemplate jdbc) {
        jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");
        jdbc.execute("DELETE FROM piece_history");
        jdbc.execute("DELETE FROM piece_attribute_values");
        jdbc.execute("DELETE FROM piece_attachments");
        jdbc.execute("DELETE FROM pieces");
        jdbc.execute("DELETE FROM piece_type_attributes");
        jdbc.execute("DELETE FROM piece_type_actions");
        jdbc.execute("DELETE FROM piece_types");
        jdbc.execute("DELETE FROM locations");
        jdbc.execute("DELETE FROM organization_audit_logs");
        jdbc.execute("DELETE FROM organization_invitations");
        jdbc.execute("DELETE FROM organization_members");
        jdbc.execute("DELETE FROM organization_slug_history");
        jdbc.execute("DELETE FROM organizations");
        jdbc.execute("DELETE FROM security_audit_entries");
        jdbc.execute("DELETE FROM user_devices");
        jdbc.execute("DELETE FROM oauth_identities");
        jdbc.execute("DELETE FROM two_factor_recovery_codes");
        jdbc.execute("DELETE FROM two_factor_setup_tokens");
        jdbc.execute("DELETE FROM invalidated_tokens");
        jdbc.execute("DELETE FROM refresh_tokens");
        jdbc.execute("DELETE FROM password_reset_tokens");
        jdbc.execute("DELETE FROM email_verification_tokens");
        jdbc.update("UPDATE users SET two_factor_enabled = FALSE, two_factor_secret = NULL, "
                + "two_factor_enabled_at = NULL WHERE email = ?", ADMIN_EMAIL);
        jdbc.execute("DELETE FROM user_username_history");
        jdbc.update("DELETE FROM users WHERE email <> ?", ADMIN_EMAIL);
        jdbc.execute("SET REFERENTIAL_INTEGRITY TRUE");
    }

    /**
     * Signs up a user and immediately marks the email as verified so the subsequent
     * login is not blocked by {@code AUTH_EMAIL_NOT_VERIFIED}. The email-verification
     * gate is exercised by dedicated tests; this helper exists for downstream feature
     * tests that just need an authenticated user.
     */
    public static String signupAndLogin(
            MockMvc mockMvc, ObjectMapper om, JdbcTemplate jdbcTemplate, String email, String username
    ) throws Exception {
        Map<String, String> body = new HashMap<>();
        body.put("name", "Test");
        body.put("lastName", "User");
        body.put("username", username);
        body.put("email", email);
        body.put("password", "password123");
        body.put("repeatPassword", "password123");
        mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isOk());
        jdbcTemplate.update("UPDATE users SET email_verified = TRUE WHERE email = ?", email);
        return login(mockMvc, om, email, "password123");
    }

    public static String login(MockMvc mockMvc, ObjectMapper om, String email, String password) throws Exception {
        Map<String, String> body = Map.of("email", email, "password", password);
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn();
        Map<?, ?> response = om.readValue(result.getResponse().getContentAsString(), Map.class);
        return (String) response.get("accessToken");
    }
}
