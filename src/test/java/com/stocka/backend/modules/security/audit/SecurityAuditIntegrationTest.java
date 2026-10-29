package com.stocka.backend.modules.security.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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

    @Nested
    @DisplayName("GET /users/me/security/activity")
    class ActivityEndpoint {

        @Test
        @DisplayName("returns the user's logins with IP and User-Agent")
        void should_returnOwnLogins_withIpAndUserAgent() throws Exception {
            String token = IntegrationTestSupport.login(mockMvc, objectMapper, ADMIN_EMAIL, ADMIN_PASSWORD);
            awaitCount("LOGIN_SUCCESS", 1);

            mockMvc.perform(get("/users/me/security/activity")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.content[0].eventType").value("LOGIN_SUCCESS"))
                    .andExpect(jsonPath("$.content[0].success").value(true))
                    .andExpect(jsonPath("$.content[0].ipAddress").value("127.0.0.1"));
        }

        @Test
        @DisplayName("leaves out every event that is not a successful login")
        void should_excludeOtherEventTypes() throws Exception {
            mockMvc.perform(post("/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "email", ADMIN_EMAIL,
                                    "password", "wrongpassword"))))
                    .andExpect(status().isUnauthorized());
            String token = IntegrationTestSupport.login(mockMvc, objectMapper, ADMIN_EMAIL, ADMIN_PASSWORD);
            awaitCount("LOGIN_FAILED", 1);
            awaitCount("LOGIN_SUCCESS", 1);

            mockMvc.perform(get("/users/me/security/activity")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.content[*].eventType", everyItem(is("LOGIN_SUCCESS"))));
        }

        @Test
        @DisplayName("does not include the logins of other users")
        void should_excludeOtherUsersLogins() throws Exception {
            IntegrationTestSupport.signupAndLogin(
                    mockMvc, objectMapper, jdbcTemplate, "other@example.com", "otheruser");
            String token = IntegrationTestSupport.login(mockMvc, objectMapper, ADMIN_EMAIL, ADMIN_PASSWORD);
            awaitCount("LOGIN_SUCCESS", 2);

            mockMvc.perform(get("/users/me/security/activity")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1));
        }

        @Test
        @DisplayName("paginates over the logins only")
        void should_paginateOverLoginsOnly() throws Exception {
            mockMvc.perform(post("/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "email", ADMIN_EMAIL,
                                    "password", "wrongpassword"))))
                    .andExpect(status().isUnauthorized());
            IntegrationTestSupport.login(mockMvc, objectMapper, ADMIN_EMAIL, ADMIN_PASSWORD);
            IntegrationTestSupport.login(mockMvc, objectMapper, ADMIN_EMAIL, ADMIN_PASSWORD);
            String token = IntegrationTestSupport.login(mockMvc, objectMapper, ADMIN_EMAIL, ADMIN_PASSWORD);
            awaitCount("LOGIN_FAILED", 1);
            awaitCount("LOGIN_SUCCESS", 3);

            mockMvc.perform(get("/users/me/security/activity")
                            .param("size", "2")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(3))
                    .andExpect(jsonPath("$.totalPages").value(2))
                    .andExpect(jsonPath("$.content.length()").value(2));
        }
    }

    private void awaitCount(String eventType, int expected) {
        await().atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(countByEventType(eventType)).isEqualTo(expected));
    }
}
