package com.stocka.backend.modules.auth;

import static com.stocka.backend.modules.organizations.IntegrationTestSupport.ADMIN_EMAIL;
import static com.stocka.backend.modules.organizations.IntegrationTestSupport.ADMIN_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.context.WebApplicationContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocka.backend.modules.organizations.IntegrationTestSupport;

/**
 * Verifies the desktop auth path (DECISIONS-AND-RISKS D4): the desktop client opts in with
 * {@code X-Stocka-Client: desktop}, gets the raw refresh token in the response body (no cookie jar),
 * and refreshes by sending it in the {@code X-Refresh-Token} header. The Tauri WebView origin is
 * allowed by CORS. The web flow is unchanged (no refresh token in the body).
 */
@SpringBootTest
@DisplayName("Desktop auth: header refresh + body refresh token + Tauri CORS")
class DesktopAuthIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    private final ObjectMapper om = new ObjectMapper();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = IntegrationTestSupport.buildMockMvc(context);
    }

    @Test
    @DisplayName("desktop login returns the refresh token in the body; web login does not")
    void desktop_login_returns_refresh_token_in_body() throws Exception {
        String refreshToken = desktopLogin();
        assertThat(refreshToken).isNotBlank();

        // Web login (no desktop header) keeps the token out of the body.
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.refreshToken").doesNotExist());
    }

    @Test
    @DisplayName("refresh via the X-Refresh-Token header rotates and returns a new token")
    void refresh_via_header_rotates_token() throws Exception {
        String first = desktopLogin();

        MvcResult result = mockMvc.perform(post("/auth/refresh")
                        .header("X-Stocka-Client", "desktop")
                        .header("X-Refresh-Token", first))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.refreshToken").exists())
                .andReturn();

        String rotated = readRefreshToken(result);
        assertThat(rotated).isNotBlank().isNotEqualTo(first);
    }

    @Test
    @DisplayName("CORS allows the Tauri WebView origin and the desktop auth header")
    void cors_allows_tauri_origin() throws Exception {
        mockMvc.perform(options("/auth/refresh")
                        .header("Origin", "tauri://localhost")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "X-Refresh-Token"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "tauri://localhost"));
    }

    private String desktopLogin() throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .header("X-Stocka-Client", "desktop")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.refreshToken").exists())
                .andReturn();
        return readRefreshToken(result);
    }

    private String credentials() throws Exception {
        return om.writeValueAsString(Map.of("email", ADMIN_EMAIL, "password", ADMIN_PASSWORD));
    }

    private String readRefreshToken(MvcResult result) throws Exception {
        Map<?, ?> body = om.readValue(result.getResponse().getContentAsString(), Map.class);
        return (String) body.get("refreshToken");
    }
}
