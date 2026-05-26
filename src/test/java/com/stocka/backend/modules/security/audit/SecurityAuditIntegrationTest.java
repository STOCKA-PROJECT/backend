package com.stocka.backend.modules.security.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocka.backend.modules.organizations.IntegrationTestSupport;

/**
 * End-to-end tests for the security audit log. Goes through the real HTTP path
 * (MockMvc) to exercise both the publisher (which captures the request-scoped
 * IP/UA) and the async listener (which persists the row).
 *
 * <p>The listener runs on a separate thread, so assertions are wrapped in
 * {@link org.awaitility.Awaitility#await()} with a short timeout.
 */
@SpringBootTest
@DisplayName("Security audit log (integration)")
class SecurityAuditIntegrationTest {

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

    private int countByEventType(String eventType) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM security_audit_entries WHERE event_type = ?",
                Integer.class, eventType);
        return count == null ? 0 : count;
    }

    @Test
    @DisplayName("LOGIN_SUCCESS row is written on a successful login")
    void should_recordLoginSuccess() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", ADMIN_EMAIL,
                                "password", ADMIN_PASSWORD))))
                .andExpect(status().isOk());

        await().atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(countByEventType("LOGIN_SUCCESS")).isEqualTo(1));
    }

    @Test
    @DisplayName("LOGIN_FAILED row is written on a wrong-password attempt")
    void should_recordLoginFailure_on_wrongPassword() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", ADMIN_EMAIL,
                                "password", "wrongpassword"))))
                .andExpect(status().isUnauthorized());

        await().atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(countByEventType("LOGIN_FAILED")).isEqualTo(1));
    }

    @Test
    @DisplayName("GET /users/me/security/activity returns the user's own audit trail")
    void should_returnPaginatedActivity() throws Exception {
        String token = IntegrationTestSupport.login(mockMvc, objectMapper, ADMIN_EMAIL, ADMIN_PASSWORD);
        await().atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(countByEventType("LOGIN_SUCCESS")).isGreaterThanOrEqualTo(1));

        mockMvc.perform(get("/users/me/security/activity")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[0].eventType").value("LOGIN_SUCCESS"))
                .andExpect(jsonPath("$.content[0].success").value(true));
    }
}
