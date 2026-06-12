package com.stocka.backend.modules.timelines.controller;

import static com.stocka.backend.modules.organizations.IntegrationTestSupport.buildMockMvc;
import static com.stocka.backend.modules.organizations.IntegrationTestSupport.signupAndLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.List;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.context.WebApplicationContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocka.backend.modules.organizations.IntegrationTestSupport;

/**
 * End-to-end coverage for the timeline editor scene. Same access rules as pieces/timelines: any
 * member (incl. SPECTATOR) can read; OWNER/MANAGER/USER can write. Covers the empty-GET, the
 * create/update round-trip, optimistic concurrency, and reference validation.
 */
@SpringBootTest
@DisplayName("Timeline scene feature (integration)")
class TimelineSceneControllerIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final ObjectMapper om = new ObjectMapper();

    private MockMvc mockMvc;
    private String ownerToken;
    private String userToken;
    private String spectatorToken;
    private String outsiderToken;
    private String acmeSlug;
    private Integer timelineId;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = buildMockMvc(context);
        IntegrationTestSupport.cleanDatabase(jdbcTemplate);

        ownerToken = signupAndLogin(mockMvc, om, jdbcTemplate, "owner@test.com", "owner");
        acmeSlug = createOrg(ownerToken, "Acme", "acme");
        timelineId = createTimeline(ownerToken, acmeSlug, "Show 1");

        outsiderToken = signupAndLogin(mockMvc, om, jdbcTemplate, "out@test.com", "outsider");
        userToken = signupAndLogin(mockMvc, om, jdbcTemplate, "user@test.com", "user");
        spectatorToken = signupAndLogin(mockMvc, om, jdbcTemplate, "spectator@test.com", "spectator");

        Integer acmeId = jdbcTemplate.queryForObject(
                "SELECT id FROM organizations WHERE slug = ?", Integer.class, "acme");
        addMember(acmeId, userIdOf("user@test.com"), "USER");
        addMember(acmeId, userIdOf("spectator@test.com"), "SPECTATOR");
    }

    @Test
    @DisplayName("GET returns 204 before the scene has ever been saved")
    void get_returns204_whenNoScene() throws Exception {
        mockMvc.perform(get(scenePath()).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("owner can create the scene, read it back, and update it bumping the version")
    void owner_can_upsertAndRead() throws Exception {
        Map<String, Object> doc = Map.of("board", Map.of("width", 1920, "height", 1080, "shape", "RECTANGLE"),
                "layers", List.of(), "items", List.of(), "tracks", List.of(), "clips", List.of());

        putScene(ownerToken, doc, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.timelineId").value(timelineId))
                .andExpect(jsonPath("$.document.board.width").value(1920));

        mockMvc.perform(get(scenePath()).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.document.board.shape").value("RECTANGLE"));

        putScene(ownerToken, doc, 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    @DisplayName("a stale version returns 409 version_conflict")
    void staleVersion_returns409() throws Exception {
        Map<String, Object> doc = Map.of("layers", List.of(), "items", List.of());
        putScene(ownerToken, doc, null).andExpect(status().isOk());

        putScene(ownerToken, doc, 99)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("timeline_scenes.version_conflict"));
    }

    @Test
    @DisplayName("a document with a dangling internal reference returns 400 invalid_reference")
    void danglingReference_returns400() throws Exception {
        Map<String, Object> doc = Map.of(
                "layers", List.of(Map.of("id", "l1")),
                "items", List.of(Map.of("id", "i1", "layerId", "l9")));

        putScene(ownerToken, doc, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("timeline_scenes.invalid_reference"));
    }

    @Test
    @DisplayName("spectator can read but cannot write the scene")
    void spectator_canRead_butCannotWrite() throws Exception {
        putScene(ownerToken, Map.of("layers", List.of()), null).andExpect(status().isOk());

        mockMvc.perform(get(scenePath()).header("Authorization", "Bearer " + spectatorToken))
                .andExpect(status().isOk());
        putScene(spectatorToken, Map.of("layers", List.of()), null).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a plain USER member can write the scene")
    void user_can_write() throws Exception {
        putScene(userToken, Map.of("layers", List.of()), null).andExpect(status().isOk());
    }

    @Test
    @DisplayName("a non-member cannot read or write the scene")
    void nonMember_isForbidden() throws Exception {
        mockMvc.perform(get(scenePath()).header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isForbidden());
        putScene(outsiderToken, Map.of("layers", List.of()), null).andExpect(status().isForbidden());
    }

    // ---------- helpers ----------

    private String scenePath() {
        return "/organizations/" + acmeSlug + "/timelines/" + timelineId + "/scene";
    }

    private ResultActions putScene(String token, Map<String, Object> document, Integer version) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("schemaVersion", 1);
        body.put("document", document);
        body.put("version", version);
        return mockMvc.perform(put(scenePath())
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(body)));
    }

    private Integer createTimeline(String token, String slug, String name) throws Exception {
        MvcResult r = mockMvc.perform(post("/organizations/" + slug + "/timelines")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("name", name))))
                .andExpect(status().isCreated())
                .andReturn();
        return (Integer) om.readValue(r.getResponse().getContentAsString(), Map.class).get("id");
    }

    private String createOrg(String token, String name, String slug) throws Exception {
        MvcResult r = mockMvc.perform(post("/organizations")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("name", name, "slug", slug))))
                .andExpect(status().isOk())
                .andReturn();
        return (String) om.readValue(r.getResponse().getContentAsString(), Map.class).get("slug");
    }

    private Integer userIdOf(String email) {
        return jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", Integer.class, email);
    }

    private void addMember(Integer orgId, Integer userId, String role) {
        Long nextId = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(id), 0) + 1 FROM organization_members", Long.class);
        jdbcTemplate.update(
                "INSERT INTO organization_members (id, user_id, organization_id, role, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                nextId, userId, orgId, role);
    }
}
