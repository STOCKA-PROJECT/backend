package com.stocka.backend.modules.timelines.controller;

import static com.stocka.backend.modules.organizations.IntegrationTestSupport.buildMockMvc;
import static com.stocka.backend.modules.organizations.IntegrationTestSupport.signupAndLogin;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.context.WebApplicationContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocka.backend.modules.organizations.IntegrationTestSupport;

/**
 * End-to-end coverage for timelines. Timelines are available to every organization (not gated) and
 * use the same access rules as pieces: any member (including SPECTATOR) can read, while OWNER,
 * MANAGER and USER can create/modify/delete.
 *
 * <p>Fixture: a regular user owns "Acme" with an extra MANAGER, USER and SPECTATOR member; a second
 * user ("outsider") is not a member.
 */
@SpringBootTest
@DisplayName("Timeline feature (integration)")
class TimelineControllerIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final ObjectMapper om = new ObjectMapper();

    private MockMvc mockMvc;
    private String ownerToken;
    private String managerToken;
    private String userToken;
    private String spectatorToken;
    private String outsiderToken;
    private String acmeSlug;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = buildMockMvc(context);
        IntegrationTestSupport.cleanDatabase(jdbcTemplate);

        ownerToken = signupAndLogin(mockMvc, om, jdbcTemplate, "owner@test.com", "owner");
        acmeSlug = createOrg(ownerToken, "Acme", "acme");

        outsiderToken = signupAndLogin(mockMvc, om, jdbcTemplate, "out@test.com", "outsider");
        managerToken = signupAndLogin(mockMvc, om, jdbcTemplate, "manager@test.com", "manager");
        userToken = signupAndLogin(mockMvc, om, jdbcTemplate, "user@test.com", "user");
        spectatorToken = signupAndLogin(mockMvc, om, jdbcTemplate, "spectator@test.com", "spectator");

        Integer acmeId = jdbcTemplate.queryForObject(
                "SELECT id FROM organizations WHERE slug = ?", Integer.class, "acme");
        addMember(acmeId, userIdOf("manager@test.com"), "MANAGER");
        addMember(acmeId, userIdOf("user@test.com"), "USER");
        addMember(acmeId, userIdOf("spectator@test.com"), "SPECTATOR");
    }

    @Test
    @DisplayName("owner can create a timeline and read it back with timestamps")
    void owner_can_createAndReadTimeline() throws Exception {
        createTimeline(ownerToken, acmeSlug, "Hito 1")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Hito 1"))
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists());

        mockMvc.perform(get(timelinesPath(acmeSlug)).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Hito 1"));
    }

    @Test
    @DisplayName("manager and plain USER members can create timelines (pieces-level write access)")
    void managerAndUser_can_createTimeline() throws Exception {
        createTimeline(managerToken, acmeSlug, "Hito manager").andExpect(status().isCreated());
        createTimeline(userToken, acmeSlug, "Hito user").andExpect(status().isCreated());
    }

    @Test
    @DisplayName("spectator can read but cannot create, modify or delete timelines")
    void spectator_canRead_butCannotWrite() throws Exception {
        MvcResult created = createTimeline(ownerToken, acmeSlug, "Hito 1")
                .andExpect(status().isCreated())
                .andReturn();
        Integer id = (Integer) om.readValue(created.getResponse().getContentAsString(), Map.class).get("id");

        mockMvc.perform(get(timelinesPath(acmeSlug)).header("Authorization", "Bearer " + spectatorToken))
                .andExpect(status().isOk());
        createTimeline(spectatorToken, acmeSlug, "Otro hito").andExpect(status().isForbidden());
        mockMvc.perform(delete(timelinesPath(acmeSlug) + "/" + id)
                        .header("Authorization", "Bearer " + spectatorToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("owner can rename a timeline via PATCH")
    void owner_can_renameTimeline() throws Exception {
        MvcResult created = createTimeline(ownerToken, acmeSlug, "Hito 1")
                .andExpect(status().isCreated())
                .andReturn();
        Integer id = (Integer) om.readValue(created.getResponse().getContentAsString(), Map.class).get("id");

        renameTimeline(ownerToken, acmeSlug, id, "Hito renombrado")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Hito renombrado"));

        mockMvc.perform(get(timelinesPath(acmeSlug)).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Hito renombrado"));
    }

    @Test
    @DisplayName("a plain USER member can rename a timeline (pieces-level write access)")
    void user_can_renameTimeline() throws Exception {
        MvcResult created = createTimeline(ownerToken, acmeSlug, "Hito 1")
                .andExpect(status().isCreated())
                .andReturn();
        Integer id = (Integer) om.readValue(created.getResponse().getContentAsString(), Map.class).get("id");

        renameTimeline(userToken, acmeSlug, id, "Hito user").andExpect(status().isOk());
    }

    @Test
    @DisplayName("renaming a timeline to a name held by another in the same org returns 409")
    void renameToDuplicate_returns409() throws Exception {
        createTimeline(ownerToken, acmeSlug, "Hito 1").andExpect(status().isCreated());
        MvcResult second = createTimeline(ownerToken, acmeSlug, "Hito 2")
                .andExpect(status().isCreated())
                .andReturn();
        Integer secondId = (Integer) om.readValue(second.getResponse().getContentAsString(), Map.class).get("id");

        renameTimeline(ownerToken, acmeSlug, secondId, "Hito 1")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("timelines.name_conflict"));
    }

    @Test
    @DisplayName("spectator cannot rename a timeline")
    void spectator_cannotRename() throws Exception {
        MvcResult created = createTimeline(ownerToken, acmeSlug, "Hito 1")
                .andExpect(status().isCreated())
                .andReturn();
        Integer id = (Integer) om.readValue(created.getResponse().getContentAsString(), Map.class).get("id");

        renameTimeline(spectatorToken, acmeSlug, id, "Otro nombre").andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a non-member cannot read or write timelines")
    void nonMember_isForbidden() throws Exception {
        mockMvc.perform(get(timelinesPath(acmeSlug)).header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isForbidden());
        createTimeline(outsiderToken, acmeSlug, "Hito ajeno").andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("creating a timeline with a blank name returns 400")
    void blankName_returns400() throws Exception {
        createTimeline(ownerToken, acmeSlug, "   ").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("creating two timelines with the same name in the same org returns 409")
    void duplicateName_returns409() throws Exception {
        createTimeline(ownerToken, acmeSlug, "Hito 1").andExpect(status().isCreated());
        createTimeline(ownerToken, acmeSlug, "Hito 1")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("timelines.name_conflict"));
    }

    @Test
    @DisplayName("soft-deleting a timeline frees the name slot for reuse")
    void softDelete_freesNameSlot() throws Exception {
        MvcResult created = createTimeline(ownerToken, acmeSlug, "Hito 1")
                .andExpect(status().isCreated())
                .andReturn();
        Integer id = (Integer) om.readValue(created.getResponse().getContentAsString(), Map.class).get("id");

        mockMvc.perform(delete(timelinesPath(acmeSlug) + "/" + id)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNoContent());

        createTimeline(ownerToken, acmeSlug, "Hito 1").andExpect(status().isCreated());
    }

    // ---------- helpers ----------

    private String timelinesPath(String slug) {
        return "/organizations/" + slug + "/timelines";
    }

    private ResultActions createTimeline(String token, String slug, String name) throws Exception {
        return mockMvc.perform(post(timelinesPath(slug))
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("name", name))));
    }

    private ResultActions renameTimeline(String token, String slug, int id, String name) throws Exception {
        return mockMvc.perform(patch(timelinesPath(slug) + "/" + id)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("name", name))));
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
