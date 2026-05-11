package com.stocka.backend.modules.security.config;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@DisplayName("CORS configuration (integration)")
class CorsConfigurationIntegrationTest {

    @Autowired private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    @Test
    @DisplayName("OPTIONS preflight from dev origin includes Access-Control-Allow-Origin")
    void should_allowPreflight_when_originIsDev() throws Exception {
        mockMvc.perform(options("/health")
                        .header("Origin", "http://localhost:3002")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "Authorization, Content-Type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3002"))
                .andExpect(header().stringValues("Access-Control-Allow-Methods",
                        org.hamcrest.Matchers.hasItem(
                                org.hamcrest.Matchers.containsString("GET"))));
    }

    @Test
    @DisplayName("OPTIONS preflight from production worker origin is allowed")
    void should_allowPreflight_when_originIsProductionWorker() throws Exception {
        mockMvc.perform(options("/auth/login")
                        .header("Origin", "https://stocka.joanmartorellcoll03.workers.dev")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "Authorization, Content-Type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin",
                        "https://stocka.joanmartorellcoll03.workers.dev"));
    }

    @Test
    @DisplayName("OPTIONS preflight from disallowed origin returns no Access-Control-Allow-Origin header")
    void should_rejectPreflight_when_originIsNotAllowed() throws Exception {
        mockMvc.perform(options("/health")
                        .header("Origin", "https://evil.example.com")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "Authorization"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    @DisplayName("Preflight response does not enable credentials by default (Bearer-only API)")
    void should_notAllowCredentials_when_bearerAuthInUse() throws Exception {
        mockMvc.perform(options("/health")
                        .header("Origin", "http://localhost:3002")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
    }
}
