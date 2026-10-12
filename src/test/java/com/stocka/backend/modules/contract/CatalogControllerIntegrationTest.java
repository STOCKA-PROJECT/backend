package com.stocka.backend.modules.contract;

import static com.stocka.backend.modules.organizations.IntegrationTestSupport.ADMIN_EMAIL;
import static com.stocka.backend.modules.organizations.IntegrationTestSupport.ADMIN_PASSWORD;
import static com.stocka.backend.modules.organizations.IntegrationTestSupport.buildMockMvc;
import static com.stocka.backend.modules.organizations.IntegrationTestSupport.login;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.context.WebApplicationContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocka.backend.modules.organizations.IntegrationTestSupport;

/**
 * The output catalog is served verbatim to any authenticated user so the frontend can render
 * kind, config and action forms from the same file the backend validates against.
 */
@SpringBootTest
@DisplayName("Output catalog endpoint (integration)")
class CatalogControllerIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final ObjectMapper om = new ObjectMapper();

    private MockMvc mockMvc;
    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = buildMockMvc(context);
        IntegrationTestSupport.cleanDatabase(jdbcTemplate);
        adminToken = login(mockMvc, om, ADMIN_EMAIL, ADMIN_PASSWORD);
    }

    @Test
    @DisplayName("an authenticated user gets the catalog with its kinds and board")
    void authenticated_getsCatalog() throws Exception {
        mockMvc.perform(get("/contract/catalog").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.format").value("stocka-catalog"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.kinds.relay.controller").value("RelayController"))
                .andExpect(jsonPath("$.kinds.led_strip.infrastructure").value(true))
                .andExpect(jsonPath("$.kinds.led_segment.actions.animate.animations.fade.params.color.required").value(true))
                .andExpect(jsonPath("$.boards.rpi4b.numbering").value("BCM"));
    }

    @Test
    @DisplayName("the response is the bundled catalog.json, unchanged")
    void response_matchesBundledResource() throws Exception {
        MvcResult result = mockMvc.perform(get("/contract/catalog").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        var served = om.readTree(result.getResponse().getContentAsString());
        var bundled = om.readTree(new ClassPathResource("contract/catalog.json").getInputStream());
        assertThat(served).isEqualTo(bundled);
    }

    @Test
    @DisplayName("an unauthenticated request is rejected with 401 auth.unauthenticated")
    void unauthenticated_isRejected() throws Exception {
        mockMvc.perform(get("/contract/catalog"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("auth.unauthenticated"));
    }
}
