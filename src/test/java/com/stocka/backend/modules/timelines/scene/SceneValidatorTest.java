package com.stocka.backend.modules.timelines.scene;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.stocka.backend.modules.contract.OutputCatalog;
import com.stocka.backend.modules.contract.OutputKind;
import com.stocka.backend.modules.piecetypes.entity.AttributeType;
import com.stocka.backend.modules.timelines.scene.SceneDocument.Board;
import com.stocka.backend.modules.timelines.scene.SceneDocument.Clip;
import com.stocka.backend.modules.timelines.scene.SceneDocument.Item;
import com.stocka.backend.modules.timelines.scene.SceneDocument.Layer;
import com.stocka.backend.modules.timelines.scene.SceneDocument.Track;

@DisplayName("SceneValidator")
class SceneValidatorTest {

    private final OutputCatalog catalog = new OutputCatalog();
    private StubSceneContext ctx;
    private List<Item> items;
    private List<Track> tracks;
    private List<Clip> clips;

    @BeforeEach
    void setUp() {
        ctx = new StubSceneContext()
                .piece(1, "Pastor", 10, "Figura", null)
                .piece(2, "Casa", 20, "Edifici", "led_segment")
                .port(100, "Fondo1", 20, "led_segment")
                .port(101, "Focos", 30, "relay")
                .action(500, "animate", 20, "Edifici", "led_segment",
                        StubSceneContext.param("animation_type", AttributeType.SELECT, true, false, "fade", false),
                        StubSceneContext.param("color", AttributeType.TEXT, true, true, null, false),
                        StubSceneContext.param("from_color", AttributeType.TEXT, false, true, null, false),
                        StubSceneContext.param("duration", AttributeType.DECIMAL, false, true, null, true))
                .action(501, "fill", 20, "Edifici", "led_segment",
                        StubSceneContext.param("color", AttributeType.TEXT, true, true, null, false),
                        StubSceneContext.param("first", AttributeType.INTEGER, false, true, null, false))
                .action(502, "wave", 10, "Figura", null,
                        StubSceneContext.param("speed", AttributeType.DECIMAL, false, true, null, false),
                        StubSceneContext.param("loud", AttributeType.BOOLEAN, false, true, null, false),
                        StubSceneContext.select("mode", false, List.of("slow", "fast")));
        items = new ArrayList<>();
        tracks = new ArrayList<>();
        clips = new ArrayList<>();
    }

    private Item item(String id, int pieceId, Integer... ports) {
        Item it = new Item(id, pieceId, "layer-1", 0, 0, 100, 100, 0, null, null, List.of(ports), null, null);
        items.add(it);
        tracks.add(new Track("t-" + id, id, "T", null));
        return it;
    }

    private Clip clip(String id, String itemId, Integer actionId, long start, long duration, Map<String, Object> params) {
        Clip c = new Clip(id, "t-" + itemId, actionId, start, duration, params, null);
        clips.add(c);
        return c;
    }

    private SceneDocument doc(long durationMs) {
        return new SceneDocument(1, new Board(1280, 720, null),
                List.of(new Layer("layer-1", "Capa 1", 0, true, false, 1)), items, tracks, clips, durationMs, 30, Map.of(), null);
    }

    private List<String> codes(long durationMs) {
        return SceneValidator.validate(doc(durationMs), ctx, catalog).stream().map(SceneIssue::code).toList();
    }

    @Nested
    @DisplayName("structure")
    class Structure {

        @Test
        @DisplayName("duplicate ids and dangling references are errors")
        void structure() {
            items.add(new Item("a", 1, "layer-1", 0, 0, 10, 10, 0, null, null, List.of(), null, null));
            items.add(new Item("a", 1, "layer-9", 0, 0, 10, 10, 0, null, null, List.of(), null, null));
            tracks.add(new Track("t", "zzz", "T", null));
            clips.add(new Clip("c", "nope", null, -1, 0, Map.of(), null));
            List<String> codes = codes(1000);
            assertThat(codes).contains("E_ID", "E_REF", "E_CLIP_TIME");
            assertThat(codes(0)).contains("E_DURATION");
        }

        @Test
        @DisplayName("a clean empty scene has no findings")
        void clean() {
            assertThat(codes(1000)).isEmpty();
        }

        @Test
        @DisplayName("an invalid button schedule is E_SCHEDULE, one finding per problem")
        void invalidSchedule() {
            SceneDocument withSchedule = doc(1000).withSchedule(new SceneDocument.Schedule("maybe",
                    List.of(new SceneDocument.ScheduleWindow(List.of("mon"), "17:00", "17:00"))));
            List<SceneIssue> issues = SceneValidator.validate(withSchedule, ctx, catalog);
            assertThat(issues).extracting(SceneIssue::code).containsExactly("E_SCHEDULE", "E_SCHEDULE");
            assertThat(issues).allSatisfy(i -> assertThat(i.message()).startsWith("Button schedule: "));
        }

        @Test
        @DisplayName("a valid button schedule adds no findings")
        void validSchedule() {
            SceneDocument withSchedule = doc(1000).withSchedule(new SceneDocument.Schedule("allow",
                    List.of(new SceneDocument.ScheduleWindow(List.of("mon"), "17:00", "21:00"))));
            assertThat(SceneValidator.validate(withSchedule, ctx, catalog)).isEmpty();
        }
    }

    @Nested
    @DisplayName("references")
    class References {

        @Test
        @DisplayName("missing pieces, stale ports and ports of another type")
        void pieceAndPorts() {
            item("i1", 99);
            item("i2", 2, 999, 101, 100);
            List<SceneIssue> issues = SceneValidator.validate(doc(1000), ctx, catalog);
            assertThat(issues).extracting(SceneIssue::code).contains("E_PIECE_MISSING", "W_PORT_STALE", "E_PORT_TYPE_MISMATCH");
            assertThat(issues.get(0).isError()).as("errors first").isTrue();
        }

        @Test
        @DisplayName("clip actions must exist and belong to the piece's types")
        void actions() {
            item("i1", 1);
            clip("c1", "i1", 999, 0, 100, Map.of());
            clip("c2", "i1", 500, 0, 100, Map.of());
            clip("c3", "i1", null, 0, 100, Map.of());
            assertThat(codes(1000)).contains("E_ACTION_UNKNOWN", "E_ACTION_NOT_OF_PIECE", "W_CLIP_NO_ACTION");
        }
    }

    @Nested
    @DisplayName("parameters")
    class Parameters {

        @Test
        @DisplayName("required dynamic params, ignored static/duration params, unknown params")
        void presence() {
            item("i1", 2, 100);
            clip("c1", "i1", 500, 0, 1000, Map.of("animation_type", "fire", "duration", 2, "extra", 1));
            List<SceneIssue> issues = SceneValidator.validate(doc(5000), ctx, catalog);
            assertThat(issues).extracting(SceneIssue::code)
                    .contains("E_PARAM_REQUIRED", "W_PARAM_IGNORED", "W_PARAM_UNKNOWN");
            assertThat(issues).filteredOn(i -> i.code().equals("E_PARAM_REQUIRED"))
                    .allMatch(i -> i.message().contains("color") && i.clipId().equals("c1"));
        }

        @Test
        @DisplayName("attribute types: integer, number, boolean, select")
        void attributeTypes() {
            item("i1", 1);
            clip("ok", "i1", 502, 0, 100, Map.of("speed", "1.5", "loud", "true", "mode", "fast"));
            clip("bad", "i1", 502, 200, 100, Map.of("speed", "fast", "loud", "yes", "mode", "medium"));
            List<SceneIssue> issues = SceneValidator.validate(doc(5000), ctx, catalog);
            assertThat(issues).filteredOn(i -> i.clipId().equals("bad")).extracting(SceneIssue::code)
                    .containsExactly("E_PARAM_TYPE", "E_PARAM_TYPE", "E_PARAM_TYPE");
            assertThat(issues).filteredOn(i -> "ok".equals(i.clipId())).isEmpty();
            item("i2", 2, 100);
            clip("int", "i2", 501, 0, 100, Map.of("color", "[1,2,3]", "first", 1.5));
            assertThat(SceneValidator.validate(doc(5000), ctx, catalog)).filteredOn(i -> "int".equals(i.clipId()))
                    .extracting(SceneIssue::code).containsExactly("E_PARAM_TYPE");
        }

        @Test
        @DisplayName("catalog types: rgb forms, ranges, enum and animation-specific params")
        void catalogRules() {
            item("i1", 2, 100);
            clip("rgb1", "i1", 501, 0, 100, Map.of("color", "[255, 0, 0]"));
            clip("rgb2", "i1", 501, 100, 100, Map.of("color", "#ff0000"));
            clip("rgb3", "i1", 501, 200, 100, Map.of("color", List.of(1, 2, 3)));
            clip("rgb4", "i1", 501, 300, 100, Map.of("color", "red"));
            clip("range", "i1", 501, 400, 100, Map.of("color", "1,2,3", "first", -1));
            List<SceneIssue> issues = SceneValidator.validate(doc(5000), ctx, catalog);
            assertThat(issues).filteredOn(i -> i.code().startsWith("E_PARAM")).extracting(SceneIssue::clipId)
                    .containsExactlyInAnyOrder("rgb4", "range");
            assertThat(SceneValidator.isRgb("#abc")).isTrue();
            assertThat(SceneValidator.isRgb("300,0,0")).isFalse();
            assertThat(SceneValidator.isRgb(List.of(1, 2))).isFalse();
        }

        @Test
        @DisplayName("a position must exist on every head the clip drives (track port or every port playing the type)")
        void positions() {
            ctx.piece(3, "Cap", 40, "Cabezal", "moving_head")
                    .head(110, "Cap1", 40, "Portal", "Estrella")
                    .head(111, "Cap2", 40, "Portal")
                    .action(503, "set", 40, "Cabezal", "moving_head",
                            StubSceneContext.param("position", AttributeType.TEXT, false, true, null, false),
                            StubSceneContext.param("from_position", AttributeType.TEXT, false, true, null, false),
                            StubSceneContext.param("intensity", AttributeType.DECIMAL, false, true, null, false),
                            StubSceneContext.param("time", AttributeType.DECIMAL, false, true, null, true));
            item("i1", 3, 110, 111);
            clip("from", "i1", 503, 6000, 1000, Map.of("from_position", "Estrella", "position", "Portal"));
            clip("both", "i1", 503, 0, 1000, Map.of("position", " portal "));
            clip("one", "i1", 503, 1000, 1000, Map.of("position", "Estrella"));
            clip("none", "i1", 503, 2000, 1000, Map.of("position", "Nada"));
            clip("blank", "i1", 503, 3000, 1000, Map.of("position", "  ", "intensity", 50));
            clip("plain", "i1", 503, 4000, 1000, Map.of("intensity", 50));
            tracks.add(new Track("tp", "i1", "Cap2", 111));
            clips.add(new Clip("track", "tp", 503, 5000, 1000, Map.of("position", "Estrella"), null));
            List<SceneIssue> issues = SceneValidator.validate(doc(10000), ctx, catalog);
            List<SceneIssue> unknown = issues.stream().filter(i -> i.code().equals("E_POSITION_UNKNOWN")).toList();
            assertThat(unknown).extracting(SceneIssue::clipId).containsExactly("from", "one", "none", "none", "track");
            assertThat(unknown.get(1).message()).contains("Cap2").contains("Estrella").contains("Portal");
            assertThat(unknown).allMatch(i -> i.itemId().equals("i1"));
            assertThat(issues).filteredOn(i -> i.clipId() != null && List.of("both", "blank", "plain").contains(i.clipId()))
                    .extracting(SceneIssue::code).doesNotContain("E_POSITION_UNKNOWN", "E_PARAM_RANGE", "E_PARAM_TYPE");
            assertThat(SceneValidator.checkCatalogValue(catalog.action(OutputKind.MOVING_HEAD, "set").orElseThrow().allParams().get("position"), 3))
                    .contains("position");
        }

        @Test
        @DisplayName("a fade without from_color warns")
        void fadeFromBlack() {
            item("i1", 2, 100);
            clip("f", "i1", 500, 0, 1000, Map.of("color", "[1,2,3]"));
            assertThat(codes(5000)).contains("W_FADE_FROM_BLACK").doesNotContain("E_PARAM_REQUIRED");
        }

        @Test
        @DisplayName("checkAttributeType honours min/max validators")
        void minMax() {
            var p = StubSceneContext.param("n", AttributeType.INTEGER, false, true, null, false);
            var v = new com.stocka.backend.modules.piecetypes.dto.AttributeValidatorsDto();
            v.setMin(new java.math.BigDecimal("1"));
            v.setMax(new java.math.BigDecimal("5"));
            p.setValidators(v);
            assertThat(SceneValidator.checkAttributeType(p, 3)).isNull();
            assertThat(SceneValidator.checkAttributeType(p, 0)).contains("at least");
            assertThat(SceneValidator.checkAttributeType(p, "9")).contains("at most");
            assertThat(SceneValidator.checkAttributeType(p, 2.5)).contains("integer");
        }
    }

    @Nested
    @DisplayName("timing and hardware")
    class Timing {

        @Test
        @DisplayName("overlaps on a track and items without ports warn; a late clip only makes the show longer")
        void warnings() {
            item("i1", 2);
            clip("a", "i1", 501, 0, 3000, Map.of("color", "[1,2,3]"));
            clip("b", "i1", 501, 1000, 3000, Map.of("color", "[1,2,3]"));
            clip("c", "i1", 501, 9000, 3000, Map.of("color", "[1,2,3]"));
            List<String> codes = codes(10_000);
            assertThat(codes).contains("W_OVERLAP", "W_NO_PORT").doesNotContain("W_CLIP_PAST_END");
            assertThat(codes).doesNotContain("E_PARAM_REQUIRED");
        }
    }


    @Nested
    @DisplayName("port roles (portTypes)")
    class PortRoles {

        @BeforeEach
        void twoRelayTypes() {
            ctx.piece(3, "Molí", List.of(40, 41), List.of("Llum", "Moviment"), List.of("relay", "relay"))
                    .port(102, "Sortida 2", 41, "relay")
                    .action(503, "turn_on", "Il·luminar", 40, "Llum", "relay")
                    .action(504, "turn_on", "Moure", 41, "Moviment", "relay");
        }

        private Item itemPlaying(String id, int pieceId, List<Integer> ports, Map<Integer, List<Integer>> portTypes) {
            Item it = new Item(id, pieceId, "layer-1", 0, 0, 100, 100, 0, null, null, ports, null, portTypes);
            items.add(it);
            tracks.add(new Track("t-" + id, id, "T", null));
            return it;
        }

        @Test
        @DisplayName("a port of another type may play a type of the piece of the same kind; each action then reaches its port")
        void rolesAccepted() {
            itemPlaying("m", 3, List.of(101, 102), Map.of(101, List.of(40)));
            clip("a", "m", 503, 0, 1000, Map.of());
            clip("b", "m", 504, 2000, 1000, Map.of());
            assertThat(codes(10_000)).doesNotContain("E_PORT_TYPE_MISMATCH", "E_PORT_KIND_MISMATCH", "W_NO_PORT",
                    "W_PORTS_TOGETHER", "W_PORT_SHARED_ROLES");
        }

        @Test
        @DisplayName("one port may play two types of the piece: both actions reach it, with a warning")
        void sharedPort() {
            itemPlaying("m", 3, List.of(102), Map.of(102, List.of(40, 41)));
            clip("a", "m", 503, 0, 1000, Map.of());
            clip("b", "m", 504, 2000, 1000, Map.of());
            List<SceneIssue> issues = SceneValidator.validate(doc(10_000), ctx, catalog);
            assertThat(issues).extracting(SceneIssue::code).contains("W_PORT_SHARED_ROLES")
                    .doesNotContain("W_NO_PORT", "W_PORTS_TOGETHER", "E_PORT_TYPE_MISMATCH");
            SceneIssue shared = issues.stream().filter(i -> i.code().equals("W_PORT_SHARED_ROLES")).findFirst().orElseThrow();
            assertThat(shared.itemId()).isEqualTo("m");
            assertThat(shared.message()).contains("Sortida 2").contains("Llum").contains("Moviment").contains("Molí");
        }

        @Test
        @DisplayName("two ports playing the same type are fine; clips of that type on a track without port warn they drive both")
        void severalPortsForType() {
            itemPlaying("m", 3, List.of(101, 102), Map.of(101, List.of(41)));
            assertThat(codes(10_000)).as("no clip: nothing to warn about").isEmpty();
            clip("a", "m", 503, 0, 1000, Map.of());
            assertThat(codes(10_000)).as("a clip of another type does not warn").containsExactly("W_NO_PORT");
            clip("b", "m", 504, 2000, 1000, Map.of());
            List<SceneIssue> issues = SceneValidator.validate(doc(10_000), ctx, catalog);
            assertThat(issues).extracting(SceneIssue::code).containsExactlyInAnyOrder("W_NO_PORT", "W_PORTS_TOGETHER");
            SceneIssue together = issues.stream().filter(i -> i.code().equals("W_PORTS_TOGETHER")).findFirst().orElseThrow();
            assertThat(together.itemId()).isEqualTo("m");
            assertThat(together.message()).contains("Focos").contains("Sortida 2").contains("Moviment").contains("Molí");
            // The same clip on a track driving one port: nothing to warn about.
            clips.clear();
            tracks.add(new Track("tp", "m", "P", 102));
            clips.add(new Clip("b", "tp", 504, 2000, 1000, Map.of(), null));
            assertThat(codes(10_000)).isEmpty();
        }

        @Test
        @DisplayName("roles the piece lacks and stale ports do not count as shared or duplicated")
        void cardinalityIgnoresInvalidRoles() {
            itemPlaying("m", 3, List.of(102, 999), Map.of(102, List.of(41, 77), 999, List.of(41)));
            clip("b", "m", 504, 2000, 1000, Map.of());
            assertThat(codes(10_000)).contains("E_PORT_TYPE_MISMATCH", "W_PORT_STALE")
                    .doesNotContain("W_PORT_SHARED_ROLES", "W_PORTS_TOGETHER");
        }

        @Test
        @DisplayName("without a role the port plays its own type, which the piece must have")
        void ownTypeMustBeOfPiece() {
            itemPlaying("m", 3, List.of(101), Map.of());
            List<SceneIssue> issues = SceneValidator.validate(doc(1000), ctx, catalog);
            assertThat(issues).extracting(SceneIssue::code).containsExactly("E_PORT_TYPE_MISMATCH");
            assertThat(issues.get(0).message()).contains("Focos").contains("30").contains("Molí");
        }

        @Test
        @DisplayName("a role of another kind is refused")
        void kindMismatch() {
            itemPlaying("m", 3, List.of(100), Map.of(100, List.of(40)));
            List<SceneIssue> issues = SceneValidator.validate(doc(1000), ctx, catalog);
            assertThat(issues).extracting(SceneIssue::code).containsExactly("E_PORT_KIND_MISMATCH");
            assertThat(issues.get(0).message()).contains("Fondo1").contains("led_segment").contains("Llum").contains("relay");
        }

        @Test
        @DisplayName("an action whose type no port plays warns and names the type")
        void noPortForType() {
            itemPlaying("m", 3, List.of(102), Map.of());
            clip("a", "m", 503, 0, 1000, Map.of());
            clip("b", "m", 504, 2000, 1000, Map.of());
            List<SceneIssue> noPort = SceneValidator.validate(doc(10_000), ctx, catalog).stream()
                    .filter(i -> i.code().equals("W_NO_PORT")).toList();
            assertThat(noPort).hasSize(1);
            assertThat(noPort.get(0).clipId()).isEqualTo("a");
            assertThat(noPort.get(0).message()).contains("Llum");
        }

        @Test
        @DisplayName("a stale port plays nothing: it warns and the action does not reach it")
        void stalePort() {
            itemPlaying("m", 3, List.of(999), Map.of(999, List.of(40)));
            clip("a", "m", 503, 0, 1000, Map.of());
            assertThat(codes(10_000)).contains("W_PORT_STALE", "W_NO_PORT").doesNotContain("E_PORT_TYPE_MISMATCH");
        }
    }

    @Nested
    @DisplayName("track ports (a track driving one port of the item)")
    class TrackPorts {

        @BeforeEach
        void twoMotorsOnOnePiece() {
            ctx.piece(3, "Molí", List.of(40, 41), List.of("Llum", "Moviment"), List.of("relay", "relay"))
                    .port(102, "Sortida 2", 41, "relay")
                    .port(103, "Sortida 3", 41, "relay")
                    .action(503, "turn_on", "Il·luminar", 40, "Llum", "relay")
                    .action(504, "turn_on", "Moure", 41, "Moviment", "relay");
            // Two relays playing «Moviment», «Focos» playing «Llum».
            Item it = new Item("m", 3, "layer-1", 0, 0, 100, 100, 0, null, null, List.of(102, 103, 101), null, Map.of(101, List.of(40)));
            items.add(it);
            tracks.add(new Track("t-m", "m", "General", null));
        }

        private void track(String id, Integer portId) {
            tracks.add(new Track(id, "m", id, portId));
        }

        private void clipOn(String id, String trackId, int actionId) {
            clips.add(new Clip(id, trackId, actionId, clips.size() * 2000L, 1000, Map.of(), null));
        }

        @Test
        @DisplayName("a clip on a track driving a port that plays its type is clean, without W_NO_PORT or W_PORTS_TOGETHER")
        void pinnedClipAccepted() {
            track("tp2", 102);
            track("tp3", 103);
            clipOn("a", "tp2", 504);
            clipOn("b", "tp3", 504);
            assertThat(codes(10_000)).isEmpty();
        }

        @Test
        @DisplayName("a clip whose type the track's port does not play is E_TRACK_PORT_MISMATCH on the clip")
        void mismatch() {
            track("tp2", 102);
            clipOn("a", "tp2", 503);
            List<SceneIssue> issues = SceneValidator.validate(doc(10_000), ctx, catalog);
            assertThat(issues).extracting(SceneIssue::code).containsExactly("E_TRACK_PORT_MISMATCH");
            assertThat(issues.get(0).clipId()).isEqualTo("a");
            assertThat(issues.get(0).itemId()).isEqualTo("m");
            assertThat(issues.get(0).message()).contains("turn_on").contains("Llum").contains("Sortida 2").contains("tp2");
        }

        @Test
        @DisplayName("a port playing two types of the piece accepts the clips of both on its track")
        void sharedPortTrack() {
            items.set(0, items.get(0).withPorts(List.of(102), Map.of(102, List.of(40, 41))));
            track("tp2", 102);
            clipOn("a", "tp2", 503);
            clipOn("b", "tp2", 504);
            assertThat(codes(10_000)).containsExactly("W_PORT_SHARED_ROLES");
        }

        @Test
        @DisplayName("a track driving a port the item is not bound to, or a stale one, is E_TRACK_PORT_UNBOUND; its clips are not checked against a port nor counted as driving every port")
        void unbound() {
            track("tp100", 100);
            track("tp999", 999);
            clipOn("a", "tp100", 504);
            clipOn("b", "tp999", 503);
            List<SceneIssue> issues = SceneValidator.validate(doc(10_000), ctx, catalog);
            assertThat(issues).extracting(SceneIssue::code).containsExactly("E_TRACK_PORT_UNBOUND", "E_TRACK_PORT_UNBOUND");
            assertThat(issues.get(0).message()).contains("tp100").contains("Fondo1").contains("not bound").contains("Molí");
            assertThat(issues.get(1).message()).contains("tp999").contains("999").contains("no longer exists");
            assertThat(issues).allMatch(i -> "m".equals(i.itemId()) && i.clipId() == null);
        }

        @Test
        @DisplayName("a track of an item or piece that does not exist is reported as a reference error only")
        void missingItem() {
            tracks.add(new Track("tz", "zzz", "Z", 102));
            assertThat(codes(10_000)).containsExactly("E_REF");
        }
    }
}
