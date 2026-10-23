package com.stocka.backend.modules.timelines.show;

import static com.stocka.backend.modules.organizations.IntegrationTestSupport.ADMIN_EMAIL;
import static com.stocka.backend.modules.organizations.IntegrationTestSupport.ADMIN_PASSWORD;
import static com.stocka.backend.modules.organizations.IntegrationTestSupport.buildMockMvc;
import static com.stocka.backend.modules.organizations.IntegrationTestSupport.login;
import static com.stocka.backend.modules.organizations.IntegrationTestSupport.signupAndLogin;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.context.WebApplicationContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocka.backend.modules.oauth.OAuthTestClient;
import com.stocka.backend.modules.organizations.IntegrationTestSupport;
import com.stocka.backend.modules.storage.R2Service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The show export end to end: the organization of the shared sample ({@code belen-2026}) is created
 * through the API (types with kind and actions, pieces, ports, an AUDIO attachment, the timeline and
 * its scene) and {@code GET …/show} must produce the sample package, hash included, with the single
 * unavoidable difference that the audio asset is named after its attachment
 * ({@code <attachmentId>-nature.mp3} instead of the sample's legacy {@code nature.mp3}). The bundle,
 * the error paths, the roles and the MCP tool are covered on the same data.
 *
 * <p>Fixture: the seeded global admin owns "Acme" (hardware enabled) with a MANAGER, a USER and a
 * SPECTATOR; an outsider is a member of nothing.
 */
@SpringBootTest
@DisplayName("Show export (integration)")
class ShowControllerIntegrationTest {

    /** Starts with an ID3 tag so the MIME sniffer reports {@code audio/mpeg}. */
    private static final byte[] NATURE_MP3 = ("ID3\u0004\u0000\u0000\u0000\u0000\u0000\u0000" + "x".repeat(64))
            .getBytes(StandardCharsets.ISO_8859_1);

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private R2Service r2;

    private final ObjectMapper om = new ObjectMapper();
    private final JsonMapper json = JsonMapper.builder().build();

    private MockMvc mockMvc;
    private String adminToken;
    private String managerToken;
    private String spectatorToken;
    private String outsiderToken;
    private String acme;
    private Integer timelineId;
    private Integer attachmentId;
    private String assetName;
    private final Map<String, Integer> types = new HashMap<>();
    private final Map<String, Integer> actions = new HashMap<>();
    private final Map<String, Integer> pieces = new HashMap<>();
    private final Map<String, Integer> ports = new HashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = buildMockMvc(context);
        IntegrationTestSupport.cleanDatabase(jdbcTemplate);
        adminToken = login(mockMvc, om, ADMIN_EMAIL, ADMIN_PASSWORD);
        acme = createOrg(adminToken, "Acme", "acme");
        createBelen2026(acme);

        outsiderToken = signupAndLogin(mockMvc, om, jdbcTemplate, "out@test.com", "outsider");
        managerToken = signupAndLogin(mockMvc, om, jdbcTemplate, "manager@test.com", "manager");
        spectatorToken = signupAndLogin(mockMvc, om, jdbcTemplate, "spectator@test.com", "spectator");
        Integer acmeId = jdbcTemplate.queryForObject("SELECT id FROM organizations WHERE slug = ?", Integer.class, acme);
        addMember(acmeId, userIdOf("manager@test.com"), "MANAGER");
        addMember(acmeId, userIdOf("spectator@test.com"), "SPECTATOR");
    }

    // ---------- fixture: the sample organization through the API ----------

    private void createBelen2026(String slug) throws Exception {
        types.put("relay", createPieceType(slug, "Relé", "relay"));
        types.put("segment", createPieceType(slug, "Edifici", "led_segment"));
        types.put("audio", createPieceType(slug, "Àudio", "audio"));
        types.put("timedRelay", createPieceType(slug, "Relé temporitzat", "relay"));

        actions.put("turn_on", createAction(slug, types.get("relay"), "turn_on", "Encendre", List.of()));
        actions.put("turn_off", createAction(slug, types.get("relay"), "turn_off", "Apagar", List.of()));
        actions.put("timed_on", createAction(slug, types.get("timedRelay"), "turn_on", "Encendre un temps", List.of(
                param("time", "Temps (s)", "DECIMAL", false, true, null, true, null))));
        actions.put("timed_off", createAction(slug, types.get("timedRelay"), "turn_off", "Apagar", List.of()));
        actions.put("fill", createAction(slug, types.get("segment"), "fill", "Omplir", List.of(
                param("color", "Color", "TEXT", true, true, null, false, null))));
        actions.put("animate", createAction(slug, types.get("segment"), "animate", "Fos", List.of(
                param("animation_type", "Animació", "SELECT", true, false, "fade", false, List.of("fade")),
                param("color", "Color", "TEXT", true, true, null, false, null),
                param("from_color", "Color inicial", "TEXT", false, true, null, false, null),
                param("duration", "Durada (s)", "DECIMAL", false, true, null, true, null))));
        actions.put("play", createAction(slug, types.get("audio"), "play", "Reproduir", List.of(
                param("audio_name", "Fitxer", "TEXT", true, true, null, false, null))));
        actions.put("stop", createAction(slug, types.get("audio"), "stop", "Aturar", List.of()));

        pieces.put("Focos", createPiece(slug, "Focos", types.get("relay")));
        pieces.put("Sembra", createPiece(slug, "Sembra", types.get("relay")));
        pieces.put("LlumsE", createPiece(slug, "LlumsE", types.get("timedRelay")));
        pieces.put("Fondo1", createPiece(slug, "Fondo1", types.get("segment")));
        pieces.put("Fondo2", createPiece(slug, "Fondo2", types.get("segment")));
        pieces.put("CasaSimple", createPiece(slug, "CasaSimple", types.get("segment")));
        pieces.put("CasaDoble", createPiece(slug, "CasaDoble", types.get("segment")));
        pieces.put("Audio", createPiece(slug, "Audio", types.get("audio")));

        ports.put("Focos", createPort(slug, Map.of("name", "Focos", "kind", "relay", "pieceTypeId", types.get("relay"), "pin", 27)));
        ports.put("Sembra", createPort(slug, Map.of("name", "Sembra", "kind", "relay", "pieceTypeId", types.get("relay"), "pin", 26)));
        ports.put("LlumsE", createPort(slug, Map.of("name", "LlumsE", "kind", "relay", "pieceTypeId", types.get("timedRelay"), "pin", 18)));
        ports.put("LedStrip3", createPort(slug, Map.of("name", "LedStrip3", "kind", "led_strip", "pin", 13,
                "config", Map.of("dma", 10, "channel", 1, "length", 300))));
        ports.put("LedStrip4", createPort(slug, Map.of("name", "LedStrip4", "kind", "led_strip", "pin", 10,
                "config", Map.of("dma", 8, "channel", 0, "length", 44))));
        ports.put("Fondo1", createSegment(slug, "Fondo1", "LedStrip3", 0, 148));
        ports.put("Fondo2", createSegment(slug, "Fondo2", "LedStrip3", 298, 149));
        ports.put("CasaSimple", createSegment(slug, "CasaSimple", "LedStrip4", 0, 15));
        ports.put("CasaDoble", createSegment(slug, "CasaDoble", "LedStrip4", 16, 43));
        ports.put("Audio", createPort(slug, Map.of("name", "Audio", "kind", "audio", "pieceTypeId", types.get("audio"))));
        ports.put("Pulsador Belen", createPort(slug, Map.of("name", "Pulsador Belen", "kind", "sensor", "pin", 20,
                "config", Map.of("onActive", "play"))));

        attachmentId = uploadAudio(slug, pieces.get("Audio"), "nature.mp3", 61000L);
        assetName = attachmentId + "-nature.mp3";

        timelineId = createTimeline(slug, Belen2026Fixture.TIMELINE_NAME);
        putScene(adminToken, slug, timelineId, sampleDocument(assetName), null).andExpect(status().isOk());
    }

    /** The sample scene with the real ids of this database and the given audio asset name. */
    private Map<String, Object> sampleDocument(String audioName) {
        List<Map<String, Object>> items = new ArrayList<>();
        List<Map<String, Object>> tracks = new ArrayList<>();
        for (String[] it : new String[][] {{"i-focos", "Focos", "Focos"}, {"i-sembra", "Sembra", "Sembra"}, {"i-llumse", "LlumsE", "LlumsE"},
                {"i-fondo1", "Fondo1", "Fondo1"}, {"i-fondo2", "Fondo2", "Fondo2"}, {"i-casasimple", "CasaSimple", "CasaSimple"},
                {"i-casadoble", "CasaDoble", "CasaDoble"}, {"i-audio", "Audio", "Audio"}}) {
            items.add(item(it[0], pieces.get(it[1]), ports.get(it[2])));
            tracks.add(Map.of("id", "t-" + it[0], "itemId", it[0], "name", it[0]));
        }
        List<Map<String, Object>> clips = new ArrayList<>();
        clips.add(clip("c01", "i-focos", "turn_on", 0, 1000, Map.of()));
        clips.add(clip("c02", "i-fondo1", "fill", 0, 1000, Map.of("color", "[255,50,0]")));
        clips.add(clip("c03", "i-fondo2", "fill", 0, 1000, Map.of("color", "[255,50,0]")));
        clips.add(clip("c04", "i-casasimple", "fill", 0, 1000, Map.of("color", "[255,50,0]")));
        clips.add(clip("c05", "i-casadoble", "fill", 0, 1000, Map.of("color", "[50,16,0]")));
        clips.add(clip("c06", "i-audio", "play", 0, 1000, Map.of("audio_name", audioName)));
        clips.add(clip("c07", "i-sembra", "turn_on", 7000, 1000, Map.of()));
        clips.add(clip("c08", "i-focos", "turn_off", 20000, 1000, Map.of()));
        clips.add(clip("c09", "i-sembra", "turn_off", 23000, 1000, Map.of()));
        clips.add(clip("c10", "i-casasimple", "animate", 24000, 3000, fade("[0,0,0]", "[255,50,0]")));
        clips.add(clip("c11", "i-casadoble", "animate", 24000, 3000, fade("[0,0,0]", "[50,16,0]")));
        clips.add(clip("c12", "i-fondo1", "animate", 25000, 10000, fade("[10,0,60]", "[255,50,0]")));
        clips.add(clip("c13", "i-fondo2", "animate", 25000, 10000, fade("[10,0,60]", "[255,50,0]")));
        clips.add(clip("c14", "i-audio", "stop", 27000, 1000, Map.of()));
        clips.add(clip("c15", "i-llumse", "timed_on", 30000, 21000, Map.of()));
        clips.add(clip("c16", "i-sembra", "turn_off", 118000, 1000, Map.of()));
        clips.add(clip("c17", "i-audio", "stop", 118000, 1000, Map.of()));
        clips.add(clip("c18", "i-focos", "turn_off", 118000, 1000, Map.of()));
        clips.add(clip("c19", "i-casasimple", "animate", 118000, 4000, fade("[0,0,0]", "[255,50,0]")));
        clips.add(clip("c20", "i-casadoble", "animate", 118000, 4000, fade("[0,0,0]", "[50,16,0]")));
        clips.add(clip("c21", "i-fondo1", "animate", 118000, 4000, fade("[0,0,0]", "[255,50,0]")));
        clips.add(clip("c22", "i-fondo2", "animate", 118000, 4000, fade("[0,0,0]", "[255,50,0]")));
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("schemaVersion", 1);
        doc.put("board", Map.of("width", 1280, "height", 720));
        doc.put("layers", List.of(Map.of("id", "layer-1", "name", "Capa 1", "z", 0, "visible", true, "locked", false, "opacity", 1)));
        doc.put("items", items);
        doc.put("tracks", tracks);
        doc.put("clips", clips);
        doc.put("durationMs", 122000);
        doc.put("fps", 30);
        return doc;
    }

    /** The sample package with the audio asset renamed after its attachment. */
    private Map<String, Object> expectedPackage(String audioName) {
        Map<String, Object> expected = json.convertValue(Belen2026Fixture.sample(), new TypeReference<LinkedHashMap<String, Object>>() {});
        @SuppressWarnings("unchecked")
        Map<String, Object> assets = (Map<String, Object>) expected.get("assets");
        assets.put("audio", List.of(audioName));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> timeline = (List<Map<String, Object>>) expected.get("timeline");
        for (Map<String, Object> ev : timeline) {
            if ("play".equals(ev.get("action"))) {
                @SuppressWarnings("unchecked")
                Map<String, Object> params = (Map<String, Object>) ev.get("params");
                params.put("audio_name", audioName);
            }
        }
        return expected;
    }

    // ---------- tests ----------

    @Nested
    @DisplayName("GET /show")
    class Show {

        @Test
        @DisplayName("golden: the sample organization exports the sample package, hash included (asset renamed after its attachment)")
        void golden() throws Exception {
            JsonNode body = showOk(adminToken);
            Map<String, Object> expected = expectedPackage(assetName);
            assertThat(ShowCanonicalJson.canonical(ShowCanonicalJson.hashInput(body)))
                    .isEqualTo(ShowCanonicalJson.canonical(ShowCanonicalJson.hashInput(expected)));
            assertThat(body.path("meta").path("hash").asText()).isEqualTo(ShowCanonicalJson.showHash(expected));
            assertThat(ShowCanonicalJson.showHash(body)).isEqualTo(body.path("meta").path("hash").asText());
            assertThat(body.path("format").asText()).isEqualTo("stocka-show");
            assertThat(body.path("version").asInt()).isEqualTo(3);
            assertThat(body.path("meta").path("name").asText()).isEqualTo(Belen2026Fixture.TIMELINE_NAME);
            assertThat(body.path("meta").path("timelineId").asInt()).isEqualTo(timelineId);
            assertThat(body.path("meta").path("exportedAt").asText()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z");
            JsonNode ref = body.path("meta").path("assets").get(0);
            assertThat(ref.path("name").asText()).isEqualTo(assetName);
            assertThat(ref.path("kind").asText()).isEqualTo("audio");
            assertThat(ref.path("pieceId").asInt()).isEqualTo(pieces.get("Audio"));
            assertThat(ref.path("attachmentId").asInt()).isEqualTo(attachmentId);
            assertThat(ref.path("mimeType").asText()).isEqualTo("audio/mpeg");
            assertThat(ref.path("sizeBytes").asLong()).isEqualTo(NATURE_MP3.length);
            assertThat(ref.path("durationMs").asLong()).isEqualTo(61000);
            // Hardware comes out in port order, like the editor lists it.
            List<String> names = new ArrayList<>();
            body.path("hardware").propertyNames().forEach(names::add);
            assertThat(names).containsExactly("Focos", "Sembra", "LlumsE", "LedStrip3", "LedStrip4", "Fondo1", "Fondo2",
                    "CasaSimple", "CasaDoble", "Audio", "Pulsador Belen");
        }

        @Test
        @DisplayName("set_schedule through scene/ops is exported as mode.schedule inside the hash; null removes it")
        void schedule() throws Exception {
            String plainHash = showOk(adminToken).path("meta").path("hash").asText();
            Map<String, Object> schedule = Map.of("rule", "allow",
                    "windows", List.of(Map.of("days", List.of("fri", "sat"), "from", "17:00", "to", "21:00")));
            sceneOps(Map.of("op", "set_schedule", "schedule", schedule)).andExpect(status().isOk());

            JsonNode body = showOk(adminToken);
            assertThat(body.path("mode").path("on").asText()).isEqualTo("once");
            assertThat(body.path("mode").path("schedule").path("rule").asText()).isEqualTo("allow");
            JsonNode window = body.path("mode").path("schedule").path("windows").get(0);
            assertThat(window.path("days").get(1).asText()).isEqualTo("sat");
            assertThat(window.path("from").asText()).isEqualTo("17:00");
            assertThat(window.path("to").asText()).isEqualTo("21:00");
            assertThat(body.path("meta").path("hash").asText()).isEqualTo(ShowCanonicalJson.showHash(body)).isNotEqualTo(plainHash);

            Map<String, Object> clear = new HashMap<>();
            clear.put("op", "set_schedule");
            clear.put("schedule", null);
            sceneOps(clear).andExpect(status().isOk());
            JsonNode again = showOk(adminToken);
            assertThat(again.path("mode").has("schedule")).isFalse();
            assertThat(again.path("meta").path("hash").asText()).isEqualTo(plainHash);
        }

        @Test
        @DisplayName("an invalid schedule is refused by set_schedule with timeline_scenes.op_invalid")
        void scheduleInvalid() throws Exception {
            Map<String, Object> schedule = Map.of("rule", "allow",
                    "windows", List.of(Map.of("days", List.of("mon"), "from", "17:00", "to", "17:00")));
            sceneOps(Map.of("op", "set_schedule", "schedule", schedule))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("timeline_scenes.op_invalid"))
                    .andExpect(jsonPath("$.params.op").value("set_schedule"));
        }

        @Test
        @DisplayName("a stored scene with an empty schedule window is 422 E_SCHEDULE_INVALID")
        void storedScheduleInvalid() throws Exception {
            Map<String, Object> doc = sampleDocument(assetName);
            doc.put("schedule", Map.of("rule", "block",
                    "windows", List.of(Map.of("days", List.of("sun"), "from", "13:00", "to", "13:00"))));
            putScene(adminToken, acme, timelineId, doc, 1).andExpect(status().isOk());
            mockMvc.perform(get(showPath(acme, timelineId)).header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.params.issues[0].code").value("E_SCHEDULE_INVALID"));
        }

        @Test
        @DisplayName("the sample's legacy asset name (no attachment id) is refused: E_ASSET_MISSING, so the exact sample hash is unreachable")
        void legacyAssetName() throws Exception {
            putScene(adminToken, acme, timelineId, sampleDocument("nature.mp3"), 1).andExpect(status().isOk());
            mockMvc.perform(get(showPath(acme, timelineId)).header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("timeline_shows.invalid"))
                    .andExpect(jsonPath("$.params.errors").value(1))
                    .andExpect(jsonPath("$.params.issues[0].code").value("E_ASSET_MISSING"))
                    .andExpect(jsonPath("$.params.issues[0].clipId").value("c06"))
                    .andExpect(jsonPath("$.params.issues[0].port").value("Audio"));
        }

        @Test
        @DisplayName("a scene with blocking errors is 422 timeline_shows.invalid with the issues and warnings")
        void invalidScene() throws Exception {
            Map<String, Object> doc = sampleDocument(assetName);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> clips = (List<Map<String, Object>>) doc.get("clips");
            clips.set(1, clip("c02", "i-fondo1", "fill", 0, 1000, Map.of()));
            clips.set(11, clip("c12", "i-fondo1", "animate", 25000, 10000, Map.of("color", "[10,0,60]")));
            putScene(adminToken, acme, timelineId, doc, 1).andExpect(status().isOk());
            mockMvc.perform(get(showPath(acme, timelineId)).header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("timeline_shows.invalid"))
                    .andExpect(jsonPath("$.params.errors").value(1))
                    .andExpect(jsonPath("$.params.issues[0].code").value("E_PARAM_REQUIRED"))
                    .andExpect(jsonPath("$.params.issues[0].level").value("error"))
                    .andExpect(jsonPath("$.params.issues[0].clipId").value("c02"))
                    .andExpect(jsonPath("$.params.warnings[0].code").value("W_FADE_FROM_BLACK"))
                    .andExpect(jsonPath("$.detail").isString());
        }

        @Test
        @DisplayName("an unknown timeline is 404 timelines.not_found; a timeline without scene is 404 timeline_shows.scene_not_found")
        void notFound() throws Exception {
            mockMvc.perform(get(showPath(acme, 999999)).header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("timelines.not_found"));
            Integer empty = createTimeline(acme, "Buida");
            mockMvc.perform(get(showPath(acme, empty)).header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("timeline_shows.scene_not_found"));
            mockMvc.perform(get(bundlePath(acme, empty)).header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("timeline_shows.scene_not_found"));
        }

        @Test
        @DisplayName("every member can read the show, SPECTATOR included; a non-member cannot")
        void roles() throws Exception {
            assertThat(showOk(spectatorToken).path("meta").path("hash").asText()).isEqualTo(showOk(managerToken).path("meta").path("hash").asText());
            mockMvc.perform(get(showPath(acme, timelineId)).header("Authorization", "Bearer " + outsiderToken))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get(bundlePath(acme, timelineId)).header("Authorization", "Bearer " + outsiderToken))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get(showPath(acme, timelineId))).andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("a piece laid out on a strip takes its LEDs from its Longitud attribute")
        void stripPieceLength() throws Exception {
            String slug = createOrg(adminToken, "Led Org", "ledorg");
            Integer stripType = createPieceTypeWithAttribute(slug, "Tira Led", "led_strip", "longitud", "Longitud");
            Integer attributeId = attributeIdOf(slug, stripType);
            Integer fill = createAction(slug, stripType, "fill", "Omplir", List.of(param("color", "Color", "TEXT", true, true, null, false, null)));
            Integer clear = createAction(slug, stripType, "clear", "Apagar", List.of());
            Integer casa = createPieceWithValue(slug, "Casa petita", stripType, attributeId, "20");
            Integer strip = createPort(slug, Map.of("name", "LedStrip5", "kind", "led_strip", "pin", 12, "pieceTypeId", stripType,
                    "config", Map.of("dma", 5, "channel", 0, "length", 50)));
            Integer tl = createTimeline(slug, "Tira");
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("schemaVersion", 1);
            doc.put("board", Map.of("width", 1280, "height", 720));
            doc.put("layers", List.of(Map.of("id", "layer-1", "name", "Capa 1", "z", 0, "visible", true, "locked", false, "opacity", 1)));
            doc.put("items", List.of(item("i-casa", casa, strip)));
            doc.put("tracks", List.of(Map.of("id", "t-i-casa", "itemId", "i-casa", "name", "Casa")));
            doc.put("clips", List.of(
                    Map.of("id", "k1", "trackId", "t-i-casa", "pieceTypeActionId", fill, "startMs", 0, "durationMs", 1000,
                            "paramValues", Map.of("color", "#ff0000", "first", 0)),
                    Map.of("id", "k2", "trackId", "t-i-casa", "pieceTypeActionId", clear, "startMs", 5000, "durationMs", 1000,
                            "paramValues", Map.of())));
            doc.put("durationMs", 10000);
            putScene(adminToken, slug, tl, doc, null).andExpect(status().isOk());
            JsonNode body = showOk(adminToken, slug, tl);
            JsonNode segment = body.path("hardware").path("Casa petita");
            assertThat(segment.path("kind").asText()).isEqualTo("led_segment");
            assertThat(segment.path("strip").asText()).isEqualTo("LedStrip5");
            assertThat(segment.path("first").asInt()).isEqualTo(0);
            assertThat(segment.path("last").asInt()).isEqualTo(19);
            assertThat(body.path("timeline").get(0).path("target").asText()).isEqualTo("Casa petita");
            assertThat(body.path("timeline").get(0).path("params").path("color").toString()).isEqualTo("[255,0,0]");
            assertThat(body.path("timeline").get(1).path("action").asText()).isEqualTo("clear");
        }
    }

    @Nested
    @DisplayName("GET /bundle")
    class Bundle {

        @Test
        @DisplayName("streams a .belen.zip with show.json, the audio asset and a manifest that checks out")
        void downloads() throws Exception {
            JsonNode show = showOk(adminToken);
            String hash = show.path("meta").path("hash").asText();
            MvcResult started = mockMvc.perform(get(bundlePath(acme, timelineId)).header("Authorization", "Bearer " + adminToken))
                    .andExpect(request().asyncStarted())
                    .andReturn();
            MvcResult done = mockMvc.perform(asyncDispatch(started))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Type", "application/zip"))
                    .andExpect(header().string("Content-Disposition",
                            "attachment; filename=\"pessebre-2026-mostra-" + hash.substring(7, 15) + ".belen.zip\""))
                    .andReturn();
            Map<String, byte[]> entries = new LinkedHashMap<>();
            Map<String, Integer> methods = new HashMap<>();
            try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(done.getResponse().getContentAsByteArray()))) {
                ZipEntry e;
                while ((e = in.getNextEntry()) != null) {
                    entries.put(e.getName(), in.readAllBytes());
                    methods.put(e.getName(), e.getMethod());
                }
            }
            assertThat(entries.keySet()).containsExactly("show.json", "assets/audio/" + assetName, "manifest.json");
            assertThat(methods.get("assets/audio/" + assetName)).isEqualTo(ZipEntry.STORED);
            assertThat(entries.get("assets/audio/" + assetName)).isEqualTo(NATURE_MP3);
            JsonNode bundledShow = json.readTree(entries.get("show.json"));
            assertThat(bundledShow.path("meta").path("hash").asText()).isEqualTo(hash);
            assertThat(ShowCanonicalJson.showHash(bundledShow)).isEqualTo(hash);
            JsonNode manifest = json.readTree(entries.get("manifest.json"));
            assertThat(manifest.path("format").asText()).isEqualTo("stocka-bundle");
            assertThat(manifest.path("version").asInt()).isEqualTo(1);
            assertThat(manifest.path("show").path("hash").asText()).isEqualTo(hash);
            assertThat(manifest.path("show").path("timelineId").asInt()).isEqualTo(timelineId);
            assertThat(manifest.path("createdBy").asText()).isEqualTo("backend");
            assertThat(manifest.path("files").size()).isEqualTo(2);
            assertThat(manifest.path("files").get(0).path("path").asText()).isEqualTo("show.json");
            assertThat(manifest.path("files").get(0).path("sha256").asText()).isEqualTo(ShowCanonicalJson.sha256Hex(entries.get("show.json")));
            assertThat(manifest.path("files").get(0).path("size").asInt()).isEqualTo(entries.get("show.json").length);
            assertThat(manifest.path("files").get(1).path("path").asText()).isEqualTo("assets/audio/" + assetName);
            assertThat(manifest.path("files").get(1).path("kind").asText()).isEqualTo("audio");
            assertThat(manifest.path("files").get(1).path("sha256").asText()).isEqualTo(ShowCanonicalJson.sha256Hex(NATURE_MP3));
            assertThat(manifest.path("files").get(1).path("size").asInt()).isEqualTo(NATURE_MP3.length);
        }

        @Test
        @DisplayName("a spectator can download the bundle")
        void spectator() throws Exception {
            mockMvc.perform(get(bundlePath(acme, timelineId)).header("Authorization", "Bearer " + spectatorToken))
                    .andExpect(request().asyncStarted());
        }

        @Test
        @DisplayName("an invalid scene fails with 422 before any byte is streamed")
        void invalid() throws Exception {
            putScene(adminToken, acme, timelineId, sampleDocument("nature.mp3"), 1).andExpect(status().isOk());
            mockMvc.perform(get(bundlePath(acme, timelineId)).header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(request().asyncNotStarted())
                    .andExpect(jsonPath("$.code").value("timeline_shows.invalid"))
                    .andExpect(jsonPath("$.params.issues[0].code").value("E_ASSET_MISSING"));
        }

        @Test
        @DisplayName("an asset whose bytes are gone from storage is 404 timeline_shows.asset_not_found")
        void assetGone() throws Exception {
            String key = jdbcTemplate.queryForObject("SELECT r2_key FROM piece_attachments WHERE id = ?", String.class, attachmentId);
            r2.delete(key);
            mockMvc.perform(get(bundlePath(acme, timelineId)).header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isNotFound())
                    .andExpect(request().asyncNotStarted())
                    .andExpect(jsonPath("$.code").value("timeline_shows.asset_not_found"))
                    .andExpect(jsonPath("$.params.asset").value(assetName));
            // The show itself is still fine: the metadata row exists.
            showOk(adminToken);
        }

        @Test
        @DisplayName("a deleted attachment makes the show invalid (E_ASSET_MISSING) rather than the bundle incomplete")
        void attachmentDeleted() throws Exception {
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .delete("/organizations/" + acme + "/pieces/" + pieces.get("Audio") + "/attachments/" + attachmentId)
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isNoContent());
            mockMvc.perform(get(bundlePath(acme, timelineId)).header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.params.issues[0].code").value("E_ASSET_MISSING"));
        }
    }

    @Nested
    @DisplayName("MCP timeline_show_export")
    class Mcp {

        @Test
        @DisplayName("is registered with the read scope and returns the same package as REST")
        void exports() throws Exception {
            OAuthTestClient oauth = new OAuthTestClient(mockMvc);
            String token = oauth.connectWithAllScopes().accessToken();
            JsonNode tools = oauth.mcp(token, "tools/list", "{}").path("result").path("tools");
            JsonNode tool = null;
            for (JsonNode t : tools) {
                if (t.path("name").asText().equals("timeline_show_export")) tool = t;
            }
            assertThat(tool).isNotNull();
            assertThat(tool.path("annotations").path("readOnlyHint").asBoolean()).isTrue();
            assertThat(tool.path("inputSchema").path("required").toString()).contains("org").contains("timelineId");
            assertThat(tool.path("outputSchema").path("properties").has("hardware")).isTrue();

            JsonNode pkg = oauth.ok(token, "timeline_show_export", "{\"org\":\"acme\",\"timelineId\":" + timelineId + "}");
            assertThat(pkg.path("meta").path("hash").asText()).isEqualTo(showOk(adminToken).path("meta").path("hash").asText());
            assertThat(pkg.path("format").asText()).isEqualTo("stocka-show");
            assertThat(pkg.path("timeline").size()).isEqualTo(22);
            assertThat(pkg.path("meta").path("assets").get(0).path("attachmentId").asInt()).isEqualTo(attachmentId);
        }

        @Test
        @DisplayName("reports blocking issues as a readable error with a hint")
        void invalid() throws Exception {
            OAuthTestClient oauth = new OAuthTestClient(mockMvc);
            String token = oauth.connectWithAllScopes().accessToken();
            putScene(adminToken, acme, timelineId, sampleDocument("nature.mp3"), 1).andExpect(status().isOk());
            JsonNode error = oauth.error(token, "timeline_show_export", "{\"org\":\"acme\",\"timelineId\":" + timelineId + "}");
            assertThat(error.path("error").asText()).isEqualTo("timeline_shows.invalid");
            assertThat(error.path("message").asText()).contains("1 blocking error");
            assertThat(error.path("hint").asText()).contains("timeline_scene_edit");
            assertThat(error.path("params").path("issues").get(0).path("code").asText()).isEqualTo("E_ASSET_MISSING");
            JsonNode missing = oauth.error(token, "timeline_show_export", "{\"org\":\"acme\",\"timelineId\":999999}");
            assertThat(missing.path("error").asText()).isEqualTo("timelines.not_found");
        }
    }

    // ---------- helpers ----------

    private String showPath(String slug, Integer id) {
        return "/organizations/" + slug + "/timelines/" + id + "/show";
    }

    private String bundlePath(String slug, Integer id) {
        return "/organizations/" + slug + "/timelines/" + id + "/bundle";
    }

    private ResultActions sceneOps(Map<String, Object> op) throws Exception {
        String scenePath = "/organizations/" + acme + "/timelines/" + timelineId + "/scene";
        MvcResult current = mockMvc.perform(get(scenePath).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk()).andReturn();
        int version = json.readTree(current.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("version").asInt();
        return mockMvc.perform(post(scenePath + "/ops")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("ops", List.of(op), "expectedVersion", version))));
    }

    private JsonNode showOk(String token) throws Exception {
        return showOk(token, acme, timelineId);
    }

    private JsonNode showOk(String token, String slug, Integer id) throws Exception {
        MvcResult r = mockMvc.perform(get(showPath(slug, id)).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("application/json")))
                .andReturn();
        return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static Map<String, Object> item(String id, Integer pieceId, Integer portId) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id);
        item.put("pieceId", pieceId);
        item.put("layerId", "layer-1");
        item.put("x", 0);
        item.put("y", 0);
        item.put("width", 100);
        item.put("height", 100);
        item.put("rotation", 0);
        item.put("ports", List.of(portId));
        return item;
    }

    private Map<String, Object> clip(String id, String itemId, String action, long startMs, long durationMs, Map<String, Object> params) {
        Map<String, Object> clip = new LinkedHashMap<>();
        clip.put("id", id);
        clip.put("trackId", "t-" + itemId);
        clip.put("pieceTypeActionId", actions.get(action));
        clip.put("startMs", startMs);
        clip.put("durationMs", durationMs);
        clip.put("paramValues", params);
        return clip;
    }

    private static Map<String, Object> fade(String color, String fromColor) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("color", color);
        p.put("from_color", fromColor);
        return p;
    }

    private static Map<String, Object> param(String name, String displayName, String type, boolean required, boolean dynamic,
                                             String staticValue, boolean isDuration, List<String> options) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", name);
        p.put("displayName", displayName);
        p.put("type", type);
        p.put("required", required);
        p.put("dynamic", dynamic);
        if (staticValue != null) p.put("staticValue", staticValue);
        p.put("isDuration", isDuration);
        if (options != null) p.put("validators", Map.of("options", options));
        return p;
    }

    private ResultActions putScene(String token, String slug, Integer id, Map<String, Object> document, Integer version) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("schemaVersion", 1);
        body.put("document", document);
        body.put("version", version);
        return mockMvc.perform(put("/organizations/" + slug + "/timelines/" + id + "/scene")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(body)));
    }

    private Integer createTimeline(String slug, String name) throws Exception {
        return idOf(mockMvc.perform(post("/organizations/" + slug + "/timelines")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("name", name)))).andExpect(status().isCreated()).andReturn());
    }

    private Integer createPieceType(String slug, String name, String kind) throws Exception {
        return idOf(mockMvc.perform(post("/organizations/" + slug + "/piece-types")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("name", name, "kind", kind)))).andExpect(status().isCreated()).andReturn());
    }

    private Integer createPieceTypeWithAttribute(String slug, String name, String kind, String attrName, String attrDisplay) throws Exception {
        Map<String, Object> attr = Map.of("name", attrName, "displayName", attrDisplay, "type", "INTEGER", "required", false);
        return idOf(mockMvc.perform(post("/organizations/" + slug + "/piece-types")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("name", name, "kind", kind, "attributes", List.of(attr)))))
                .andExpect(status().isCreated()).andReturn());
    }

    private Integer attributeIdOf(String slug, Integer typeId) throws Exception {
        MvcResult r = mockMvc.perform(get("/organizations/" + slug + "/piece-types/" + typeId)
                .header("Authorization", "Bearer " + adminToken)).andExpect(status().isOk()).andReturn();
        return json.readTree(r.getResponse().getContentAsString()).path("attributes").get(0).path("id").asInt();
    }

    private Integer createAction(String slug, Integer typeId, String name, String displayName, List<Map<String, Object>> parameters) throws Exception {
        return idOf(mockMvc.perform(post("/organizations/" + slug + "/piece-types/" + typeId + "/actions")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("name", name, "displayName", displayName, "parameters", parameters))))
                .andExpect(status().isCreated()).andReturn());
    }

    private Integer createPiece(String slug, String name, Integer typeId) throws Exception {
        return idOf(mockMvc.perform(post("/organizations/" + slug + "/pieces")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("name", name, "pieceTypeIds", List.of(typeId)))))
                .andExpect(status().isCreated()).andReturn());
    }

    private Integer createPieceWithValue(String slug, String name, Integer typeId, Integer attributeId, String value) throws Exception {
        Map<String, Object> body = Map.of("name", name, "pieceTypeIds", List.of(typeId),
                "attributeValues", List.of(Map.of("attributeId", attributeId, "value", value)));
        return idOf(mockMvc.perform(post("/organizations/" + slug + "/pieces")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(body))).andExpect(status().isCreated()).andReturn());
    }

    private Integer createPort(String slug, Map<String, Object> body) throws Exception {
        return idOf(mockMvc.perform(post("/organizations/" + slug + "/ports")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(body))).andExpect(status().isCreated()).andReturn());
    }

    private Integer createSegment(String slug, String name, String strip, int first, int last) throws Exception {
        return createPort(slug, Map.of("name", name, "kind", "led_segment", "pieceTypeId", types.get("segment"),
                "config", Map.of("strip", strip, "first", first, "last", last)));
    }

    private Integer uploadAudio(String slug, Integer pieceId, String fileName, Long durationMs) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", fileName, "audio/mpeg", NATURE_MP3);
        return idOf(mockMvc.perform(multipart("/organizations/" + slug + "/pieces/" + pieceId + "/attachments")
                .file(file)
                .param("kind", "AUDIO")
                .param("durationMs", String.valueOf(durationMs))
                .header("Authorization", "Bearer " + adminToken)).andExpect(status().isCreated()).andReturn());
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

    private Integer idOf(MvcResult result) throws Exception {
        return (Integer) om.readValue(result.getResponse().getContentAsString(), Map.class).get("id");
    }

    private Integer userIdOf(String email) {
        return jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", Integer.class, email);
    }

    /**
     * Inserts a membership by JDBC. The id sits far above the ones Hibernate's sequence hands out, so
     * organizations created later in a test (which add their OWNER row) never collide with it.
     */
    private void addMember(Integer orgId, Integer userId, String role) {
        Long nextId = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(id), 0) + 100000 FROM organization_members", Long.class);
        jdbcTemplate.update(
                "INSERT INTO organization_members (id, user_id, organization_id, role, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                nextId, userId, orgId, role);
    }
}
