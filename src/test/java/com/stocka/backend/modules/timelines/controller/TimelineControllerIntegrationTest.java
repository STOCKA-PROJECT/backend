package com.stocka.backend.modules.timelines.controller;

import static com.stocka.backend.modules.organizations.IntegrationTestSupport.buildMockMvc;
import static com.stocka.backend.modules.organizations.IntegrationTestSupport.signupAndLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.List;
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
    private String otherSlug;

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
        // Before addMember: its MAX(id)+1 rows would collide with the ids the API takes afterwards.
        otherSlug = createOrg(outsiderToken, "Other", "other");

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

    @Nested
    @DisplayName("POST /timelines/{id}/duplicate")
    class Duplicate {

        private final Map<String, Object> document = Map.of(
                "board", Map.of("width", 1920, "height", 1080, "shape", "RECTANGLE"),
                "layers", List.of(Map.of("id", "layer-1", "name", "Fons")),
                "items", List.of(),
                "tracks", List.of(),
                "clips", List.of(),
                "editorOnly", "kept");

        private Integer sourceId;

        @BeforeEach
        void createSource() throws Exception {
            sourceId = idOf(createTimeline(ownerToken, acmeSlug, "Belén 2026").andExpect(status().isCreated()));
        }

        @Test
        @DisplayName("copies the timeline and its scene; the copy's scene starts at version 1")
        void owner_duplicatesTimelineWithScene() throws Exception {
            putScene(ownerToken, sourceId, document, null).andExpect(status().isOk());
            putScene(ownerToken, sourceId, document, 1).andExpect(status().isOk());

            Integer copyId = idOf(duplicate(ownerToken, acmeSlug, sourceId, "ca", null)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.name").value("Belén 2026 (còpia)"))
                    .andExpect(jsonPath("$.createdAt").exists()));

            assertThat(copyId).isNotEqualTo(sourceId);
            Map<String, Object> copied = sceneRow(copyId);
            Map<String, Object> original = sceneRow(sourceId);
            assertThat(copied.get("DOCUMENT")).isEqualTo(original.get("DOCUMENT"));
            assertThat(copied.get("SCHEMA_VERSION")).isEqualTo(original.get("SCHEMA_VERSION"));
            assertThat(((Number) copied.get("VERSION")).intValue()).isEqualTo(1);
            assertThat(((Number) original.get("VERSION")).intValue()).isEqualTo(2);
            mockMvc.perform(get(timelinesPath(acmeSlug) + "/" + copyId + "/scene")
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.version").value(1))
                    .andExpect(jsonPath("$.document.layers[0].name").value("Fons"))
                    .andExpect(jsonPath("$.document.editorOnly").value("kept"));
        }

        @Test
        @DisplayName("the copy is listed next to the source")
        void copy_isListed() throws Exception {
            duplicate(ownerToken, acmeSlug, sourceId, "ca", null).andExpect(status().isCreated());

            mockMvc.perform(get(timelinesPath(acmeSlug)).header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2));
        }

        @Test
        @DisplayName("a timeline that was never edited gives a copy without scene")
        void sourceWithoutScene_givesCopyWithoutScene() throws Exception {
            Integer copyId = idOf(duplicate(ownerToken, acmeSlug, sourceId, "ca", null)
                    .andExpect(status().isCreated()));

            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM timeline_scenes WHERE timeline_id = ?", Integer.class, copyId)).isZero();
            mockMvc.perform(get(timelinesPath(acmeSlug) + "/" + copyId + "/scene")
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("editing the copy leaves the source untouched")
        void copy_isIndependent() throws Exception {
            putScene(ownerToken, sourceId, document, null).andExpect(status().isOk());
            Integer copyId = idOf(duplicate(ownerToken, acmeSlug, sourceId, "ca", null)
                    .andExpect(status().isCreated()));
            String before = (String) sceneRow(sourceId).get("DOCUMENT");

            putScene(ownerToken, copyId, Map.of("layers", List.of(), "items", List.of()), 1)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.version").value(2));

            assertThat(sceneRow(sourceId).get("DOCUMENT")).isEqualTo(before);
            assertThat(((Number) sceneRow(sourceId).get("VERSION")).intValue()).isEqualTo(1);
        }

        @Test
        @DisplayName("numbers the next copies: (còpia 2), (còpia 3)")
        void secondAndThirdCopies_areNumbered() throws Exception {
            duplicate(ownerToken, acmeSlug, sourceId, "ca", null)
                    .andExpect(jsonPath("$.name").value("Belén 2026 (còpia)"));
            duplicate(ownerToken, acmeSlug, sourceId, "ca", null)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.name").value("Belén 2026 (còpia 2)"));
            duplicate(ownerToken, acmeSlug, sourceId, "ca", null)
                    .andExpect(jsonPath("$.name").value("Belén 2026 (còpia 3)"));
        }

        @Test
        @DisplayName("the automatic name follows Accept-Language (es, en) and defaults to Spanish")
        void automaticName_followsAcceptLanguage() throws Exception {
            duplicate(ownerToken, acmeSlug, sourceId, "es", null)
                    .andExpect(jsonPath("$.name").value("Belén 2026 (copia)"));
            duplicate(ownerToken, acmeSlug, sourceId, "en", null)
                    .andExpect(jsonPath("$.name").value("Belén 2026 (copy)"));
            duplicate(ownerToken, acmeSlug, sourceId, null, null)
                    .andExpect(jsonPath("$.name").value("Belén 2026 (copia 2)"));
        }

        @Test
        @DisplayName("an empty JSON body behaves like no body")
        void emptyBody_usesAutomaticName() throws Exception {
            mockMvc.perform(post(duplicatePath(acmeSlug, sourceId))
                            .header("Authorization", "Bearer " + ownerToken)
                            .header("Accept-Language", "ca")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.name").value("Belén 2026 (còpia)"));
        }

        @Test
        @DisplayName("a soft-deleted copy frees its name for the next copy")
        void softDeletedCopy_freesName() throws Exception {
            Integer copyId = idOf(duplicate(ownerToken, acmeSlug, sourceId, "ca", null)
                    .andExpect(status().isCreated()));
            mockMvc.perform(delete(timelinesPath(acmeSlug) + "/" + copyId)
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isNoContent());

            duplicate(ownerToken, acmeSlug, sourceId, "ca", null)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.name").value("Belén 2026 (còpia)"));
        }

        @Test
        @DisplayName("an explicit name is used as given (trimmed)")
        void explicitName_isUsed() throws Exception {
            duplicate(ownerToken, acmeSlug, sourceId, "ca", "  Belén 2027 ")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.name").value("Belén 2027"));
        }

        @Test
        @DisplayName("an explicit name already taken returns 409 and creates nothing")
        void explicitTakenName_returns409() throws Exception {
            duplicate(ownerToken, acmeSlug, sourceId, "ca", "Belén 2026")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("timelines.name_conflict"));

            assertThat(activeTimelines()).isEqualTo(1);
        }

        @Test
        @DisplayName("a blank explicit name returns 400")
        void blankExplicitName_returns400() throws Exception {
            duplicate(ownerToken, acmeSlug, sourceId, "ca", "   ").andExpect(status().isBadRequest());

            assertThat(activeTimelines()).isEqualTo(1);
        }

        @Test
        @DisplayName("MANAGER and USER members can duplicate")
        void managerAndUser_canDuplicate() throws Exception {
            duplicate(managerToken, acmeSlug, sourceId, "ca", null).andExpect(status().isCreated());
            duplicate(userToken, acmeSlug, sourceId, "ca", null).andExpect(status().isCreated());
        }

        @Test
        @DisplayName("a SPECTATOR cannot duplicate (403)")
        void spectator_isForbidden() throws Exception {
            duplicate(spectatorToken, acmeSlug, sourceId, "ca", null).andExpect(status().isForbidden());

            assertThat(activeTimelines()).isEqualTo(1);
        }

        @Test
        @DisplayName("a non-member cannot duplicate (403)")
        void outsider_isForbidden() throws Exception {
            duplicate(outsiderToken, acmeSlug, sourceId, "ca", null).andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("an unknown timeline returns 404")
        void unknownTimeline_returns404() throws Exception {
            duplicate(ownerToken, acmeSlug, 999_999, "ca", null)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("timelines.not_found"));
        }

        @Test
        @DisplayName("a soft-deleted timeline returns 404")
        void softDeletedTimeline_returns404() throws Exception {
            mockMvc.perform(delete(timelinesPath(acmeSlug) + "/" + sourceId)
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isNoContent());

            duplicate(ownerToken, acmeSlug, sourceId, "ca", null)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("timelines.not_found"));
        }

        @Test
        @DisplayName("a timeline of another organization returns 404 through this organization")
        void foreignTimeline_returns404() throws Exception {
            Integer foreignId = idOf(createTimeline(outsiderToken, otherSlug, "Aliè").andExpect(status().isCreated()));

            duplicate(ownerToken, acmeSlug, foreignId, "ca", null)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("timelines.not_found"));
            mockMvc.perform(get(timelinesPath(otherSlug)).header("Authorization", "Bearer " + outsiderToken))
                    .andExpect(jsonPath("$.length()").value(1));
            assertThat(activeTimelines()).isEqualTo(1);
        }

        private ResultActions duplicate(String token, String slug, Integer id, String language, String name)
                throws Exception {
            var request = post(duplicatePath(slug, id)).header("Authorization", "Bearer " + token);
            if (language != null) {
                request.header("Accept-Language", language);
            }
            if (name != null) {
                request.contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(Map.of("name", name)));
            }
            return mockMvc.perform(request);
        }

        private ResultActions putScene(String token, Integer timelineId, Map<String, Object> doc, Integer version)
                throws Exception {
            Map<String, Object> body = new HashMap<>();
            body.put("schemaVersion", 1);
            body.put("document", doc);
            body.put("version", version);
            return mockMvc.perform(put(timelinesPath(acmeSlug) + "/" + timelineId + "/scene")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(om.writeValueAsString(body)));
        }

        private Map<String, Object> sceneRow(Integer timelineId) {
            return jdbcTemplate.queryForMap(
                    "SELECT document, schema_version, version FROM timeline_scenes "
                            + "WHERE timeline_id = ? AND deleted_at IS NULL", timelineId);
        }

        private int activeTimelines() throws Exception {
            String body = mockMvc.perform(get(timelinesPath(acmeSlug)).header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            return om.readValue(body, List.class).size();
        }

        private String duplicatePath(String slug, Integer id) {
            return timelinesPath(slug) + "/" + id + "/duplicate";
        }
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

    private Integer idOf(ResultActions result) throws Exception {
        return (Integer) om.readValue(result.andReturn().getResponse().getContentAsString(), Map.class).get("id");
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
