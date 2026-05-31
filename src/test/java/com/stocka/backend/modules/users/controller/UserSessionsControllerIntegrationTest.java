package com.stocka.backend.modules.users.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
 * Exercises the /users/me/sessions endpoints (Feature 6) end-to-end. Each test
 * starts from a clean DB and uses the admin seed to authenticate, mirroring
 * the rest of the integration suite.
 */
@SpringBootTest
@DisplayName("/users/me/sessions (integration)")
class UserSessionsControllerIntegrationTest {

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

    private String loginAs(String userAgent) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .header("User-Agent", userAgent)
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
    @DisplayName("GET — lists every active session and flags the current one")
    void should_listSessions_and_flagCurrent() throws Exception {
        loginAs("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36");
        String secondToken = loginAs("Mozilla/5.0 (iPhone; CPU iPhone OS 17_0) AppleWebKit/605.1.15 Safari/605.1.15");

        MvcResult result = mockMvc.perform(get("/users/me/sessions")
                        .header("Authorization", "Bearer " + secondToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        int currentCount = 0;
        for (JsonNode node : body) {
            if (node.get("current").asBoolean()) currentCount++;
        }
        assertThat(currentCount).isEqualTo(1);
    }

    @Test
    @DisplayName("DELETE /{id} — revokes the device and wipes its refresh family")
    void should_revokeDevice_and_wipeFamily() throws Exception {
        String firstToken = loginAs("Mozilla/5.0 Chrome/120.0.0.0");
        loginAs("Mozilla/5.0 (iPhone) Safari/605.1.15");

        MvcResult listResult = mockMvc.perform(get("/users/me/sessions")
                        .header("Authorization", "Bearer " + firstToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(listResult.getResponse().getContentAsString());
        Long otherDeviceId = null;
        for (JsonNode node : body) {
            if (!node.get("current").asBoolean()) otherDeviceId = node.get("id").asLong();
        }
        assertThat(otherDeviceId).isNotNull();

        mockMvc.perform(delete("/users/me/sessions/" + otherDeviceId)
                        .header("Authorization", "Bearer " + firstToken))
                .andExpect(status().isNoContent());

        Integer activeFamilies = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE revoked_at IS NULL",
                Integer.class);
        assertThat(activeFamilies).isEqualTo(1);
    }

    @Test
    @DisplayName("DELETE — bulk revokes everything except the current session")
    void should_revokeEveryOtherSession_keepingCurrent() throws Exception {
        loginAs("Mozilla/5.0 Chrome/120.0.0.0");
        loginAs("Mozilla/5.0 (iPhone) Safari/605.1.15");
        String currentToken = loginAs("Mozilla/5.0 Firefox/124.0");

        mockMvc.perform(delete("/users/me/sessions")
                        .header("Authorization", "Bearer " + currentToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/users/me/sessions")
                        .header("Authorization", "Bearer " + currentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].current").value(true));
    }

    @Test
    @DisplayName("PATCH /{id} — renames the device")
    void should_renameDevice() throws Exception {
        String token = loginAs("Mozilla/5.0 Chrome/120.0.0.0");

        MvcResult listResult = mockMvc.perform(get("/users/me/sessions")
                        .header("Authorization", "Bearer " + token))
                .andReturn();
        Long deviceId = objectMapper.readTree(listResult.getResponse().getContentAsString())
                .get(0).get("id").asLong();

        mockMvc.perform(patch("/users/me/sessions/" + deviceId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("displayName", "My laptop"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("My laptop"));
    }
}
