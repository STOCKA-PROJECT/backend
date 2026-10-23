package com.stocka.backend.modules.timelines.show;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.stocka.backend.modules.contract.OutputCatalog;
import com.stocka.backend.modules.piecetypes.entity.AttributeType;
import com.stocka.backend.modules.timelines.scene.SceneDocument;
import com.stocka.backend.modules.timelines.scene.SceneDocument.Clip;
import com.stocka.backend.modules.timelines.scene.SceneDocument.Item;
import com.stocka.backend.modules.timelines.scene.SceneDocument.Track;
import com.stocka.backend.modules.timelines.scene.StubSceneContext;
import com.stocka.backend.modules.timelines.show.ShowPackage.ShowEvent;
import com.stocka.backend.modules.timelines.show.ShowPackage.ShowMode;
import com.stocka.backend.modules.timelines.show.ShowPackageBuilder.Assembled;

@DisplayName("ShowPackageBuilder")
class ShowPackageBuilderTest {

    private final ShowPackageBuilder builder = new ShowPackageBuilder(new OutputCatalog());

    private static List<String> codes(List<ShowIssue> issues) {
        return issues.stream().map(ShowIssue::code).toList();
    }

    private static List<ShowIssue> errors(Assembled assembled) {
        return assembled.issues().stream().filter(ShowIssue::isError).toList();
    }

    private static List<ShowIssue> warnings(Assembled assembled) {
        return assembled.issues().stream().filter(i -> !i.isError()).toList();
    }

    /** The fixture document with mutable lists, so tests can tweak one clip or item. */
    private static SceneDocument doc(List<Item> items, List<Clip> clips) {
        List<Track> tracks = new ArrayList<>();
        for (Item i : items) {
            tracks.add(new Track("t-" + i.id(), i.id(), i.id()));
        }
        return Belen2026Fixture.document(items, tracks, clips);
    }

    private static List<Item> items() {
        return new ArrayList<>(Belen2026Fixture.items());
    }

    private static List<Clip> clips() {
        return new ArrayList<>(Belen2026Fixture.clips());
    }

    private static Item withPorts(Item item, Integer... ports) {
        return new Item(item.id(), item.pieceId(), item.layerId(), item.x(), item.y(), item.width(), item.height(),
                item.rotation(), item.widthAttr(), item.heightAttr(), List.of(ports), item.ledPath());
    }

    private static Clip withParams(Clip clip, Map<String, Object> params) {
        return new Clip(clip.id(), clip.trackId(), clip.pieceTypeActionId(), clip.startMs(), clip.durationMs(), params, clip.label());
    }

    private static Clip withTiming(Clip clip, long startMs, long durationMs) {
        return new Clip(clip.id(), clip.trackId(), clip.pieceTypeActionId(), startMs, durationMs, clip.paramValues(), clip.label());
    }

    private static Clip withAction(Clip clip, Integer actionId) {
        return new Clip(clip.id(), clip.trackId(), actionId, clip.startMs(), clip.durationMs(), clip.paramValues(), clip.label());
    }

    private static <T> void replace(List<T> list, Predicate<T> which, UnaryOperator<T> change) {
        for (int i = 0; i < list.size(); i++) {
            if (which.test(list.get(i))) {
                list.set(i, change.apply(list.get(i)));
            }
        }
    }

    private static ShowEvent find(List<ShowEvent> timeline, String target, String action) {
        return timeline.stream().filter(e -> e.target().equals(target) && e.action().equals(action)).findFirst().orElseThrow();
    }

    private static ShowEvent find(List<ShowEvent> timeline, String target, long t) {
        return timeline.stream().filter(e -> e.target().equals(target) && e.t() == t).findFirst().orElseThrow();
    }

    @Nested
    @DisplayName("golden sample")
    class Golden {

        @Test
        @DisplayName("exports the 2026 slice exactly as the shared sample, hash included")
        void exportsSample() {
            ShowBuildResult result = builder.build(Belen2026Fixture.input());
            assertThat(result.errors()).isEmpty();
            assertThat(result.warnings()).isEmpty();
            assertThat(result.pkg().meta().hash()).isEqualTo(Belen2026Fixture.HASH);
            assertThat(ShowCanonicalJson.canonical(ShowCanonicalJson.hashInput(result.pkg())))
                    .isEqualTo(ShowCanonicalJson.canonical(ShowCanonicalJson.hashInput(Belen2026Fixture.sample())));
            assertThat(ShowCanonicalJson.pretty(result.pkg()) + "\n").isEqualTo(Belen2026Fixture.sampleText());
        }

        @Test
        @DisplayName("the hash ignores meta and changes with content")
        void hashIgnoresMeta() {
            ShowPackage pkg = builder.build(Belen2026Fixture.input()).pkg();
            ShowPackage renamed = builder.build(Belen2026Fixture.input().withTimeline("Altre nom", 42)
                    .withNow(() -> "2030-01-01T00:00:00.000Z")).pkg();
            assertThat(renamed.meta().hash()).isEqualTo(pkg.meta().hash());
            assertThat(renamed.meta().name()).isEqualTo("Altre nom");
            assertThat(renamed.meta().timelineId()).isEqualTo(42);
            List<Clip> clips = clips();
            replace(clips, c -> c.id().equals("c07"), c -> withTiming(c, 7500, c.durationMs()));
            ShowPackage changed = builder.build(Belen2026Fixture.input(doc(items(), clips))).pkg();
            assertThat(changed.meta().hash()).isNotEqualTo(pkg.meta().hash());
        }

        @Test
        @DisplayName("uses the default mode and board; another mode changes the hash")
        void defaults() {
            ShowPackage pkg = builder.build(Belen2026Fixture.input()).pkg();
            assertThat(pkg.mode()).isEqualTo(new ShowMode("once", 1));
            assertThat(pkg.board()).isEqualTo("rpi4b");
            assertThat(pkg.format()).isEqualTo("stocka-show");
            assertThat(pkg.version()).isEqualTo(3);
            assertThat(pkg.meta().assets()).isNull();
            ShowPackage pulse = builder.build(Belen2026Fixture.input().withMode(new ShowMode("pulse", 3))).pkg();
            assertThat(pulse.mode()).isEqualTo(new ShowMode("pulse", 3));
            assertThat(pulse.meta().hash()).isNotEqualTo(pkg.meta().hash());
        }

        @Test
        @DisplayName("the scene's button schedule goes to mode.schedule and into the hash")
        void schedule() {
            SceneDocument.Schedule schedule = new SceneDocument.Schedule("allow",
                    List.of(new SceneDocument.ScheduleWindow(List.of("fri", "sat"), "17:00", "21:00")));
            ShowPackage plain = builder.build(Belen2026Fixture.input()).pkg();
            ShowBuildResult result = builder.build(Belen2026Fixture.input(Belen2026Fixture.document().withSchedule(schedule)));
            assertThat(result.errors()).isEmpty();
            assertThat(result.warnings()).isEmpty();
            assertThat(result.pkg().mode()).isEqualTo(new ShowMode("once", 1, schedule));
            assertThat(result.pkg().meta().hash()).isNotEqualTo(plain.meta().hash())
                    // same value in rpi4-system tests/test_show_hash.py and the editor's showPackage.test.ts
                    .isEqualTo("sha256:e5dba297bdd5ca75d6b85d3f51a6b19b2cb4d0c43a4dd0d6ec7a04da1d809df2");
            assertThat(ShowCanonicalJson.canonical(ShowCanonicalJson.hashInput(result.pkg())))
                    .contains("\"mode\":{\"cycles\":1,\"on\":\"once\",\"schedule\":{\"rule\":\"allow\",\"windows\":"
                            + "[{\"days\":[\"fri\",\"sat\"],\"from\":\"17:00\",\"to\":\"21:00\"}]}}");
            assertThat(ShowCanonicalJson.canonical(ShowCanonicalJson.hashInput(plain))).doesNotContain("schedule");
        }

        @Test
        @DisplayName("a schedule given in the mode wins over the scene's")
        void scheduleInMode() {
            SceneDocument.Schedule scene = new SceneDocument.Schedule("allow",
                    List.of(new SceneDocument.ScheduleWindow(List.of("mon"), "17:00", "21:00")));
            SceneDocument.Schedule explicit = new SceneDocument.Schedule("block",
                    List.of(new SceneDocument.ScheduleWindow(List.of("sun"), "13:00", "16:00")));
            ShowPackage pkg = builder.build(Belen2026Fixture.input(Belen2026Fixture.document().withSchedule(scene))
                    .withMode(new ShowMode("pulse", 2, explicit))).pkg();
            assertThat(pkg.mode().schedule()).isEqualTo(explicit);
        }

        @Test
        @DisplayName("an invalid schedule blocks the export; a useless one only warns")
        void scheduleFindings() {
            SceneDocument.Schedule empty = new SceneDocument.Schedule("allow",
                    List.of(new SceneDocument.ScheduleWindow(List.of("mon"), "17:00", "17:00")));
            ShowBuildResult invalid = builder.build(Belen2026Fixture.input(Belen2026Fixture.document().withSchedule(empty)));
            assertThat(invalid.pkg()).isNull();
            assertThat(codes(invalid.errors())).containsExactly("E_SCHEDULE_INVALID");
            SceneDocument.Schedule ok = new SceneDocument.Schedule("allow",
                    List.of(new SceneDocument.ScheduleWindow(List.of("mon"), "17:00", "21:00")));
            ShowBuildResult loop = builder.build(Belen2026Fixture.input(Belen2026Fixture.document().withSchedule(ok))
                    .withMode(new ShowMode("loop", 1)));
            assertThat(loop.pkg()).isNotNull();
            assertThat(codes(loop.warnings())).containsExactly("W_SCHEDULE_UNUSED");
        }

        @Test
        @DisplayName("a blank timeline name becomes Show")
        void blankName() {
            assertThat(builder.build(Belen2026Fixture.input().withTimeline("  ", null)).pkg().meta().name()).isEqualTo("Show");
        }
    }

    @Nested
    @DisplayName("hardware")
    class Hardware {

        @Test
        @DisplayName("maps every kind: kind, pin when the kind has one, config keys; port order kept")
        void mapsKinds() {
            List<ShowPort> ports = new ArrayList<>(Belen2026Fixture.ports());
            Map<String, Object> motor = Belen2026Fixture.config("pinForward", 3, "pinReverse", 4, "maxTimeForward", 20, "maxTimeReverse", 15, "pin", 99, "kind", "x", "note", null);
            ports.add(Belen2026Fixture.port(12, "Angel", "motor", 9, null, motor));
            Assembled assembled = builder.assemble(ShowBuildInput.of(Belen2026Fixture.document(), Belen2026Fixture.context(), ports));
            assertThat(assembled.hardware().keySet()).containsExactly("Focos", "Sembra", "LlumsE", "LedStrip3", "LedStrip4", "Fondo1",
                    "Fondo2", "CasaSimple", "CasaDoble", "Audio", "Pulsador Belen", "Angel");
            assertThat(assembled.hardware().get("Focos")).containsExactly(Map.entry("kind", "relay"), Map.entry("pin", 27));
            assertThat(assembled.hardware().get("LedStrip3")).containsExactly(Map.entry("kind", "led_strip"), Map.entry("pin", 13),
                    Map.entry("dma", 10), Map.entry("channel", 1), Map.entry("length", 300));
            assertThat(assembled.hardware().get("Fondo2")).containsExactly(Map.entry("kind", "led_segment"),
                    Map.entry("strip", "LedStrip3"), Map.entry("first", 298), Map.entry("last", 149));
            assertThat(assembled.hardware().get("Audio")).containsExactly(Map.entry("kind", "audio"));
            assertThat(assembled.hardware().get("Pulsador Belen")).containsExactly(Map.entry("kind", "sensor"), Map.entry("pin", 20),
                    Map.entry("onActive", "play"));
            // Motors have no single pin: the stray pin/kind/null config keys never reach the package.
            assertThat(assembled.hardware().get("Angel")).containsExactly(Map.entry("kind", "motor"), Map.entry("pinForward", 3),
                    Map.entry("pinReverse", 4), Map.entry("maxTimeForward", 20), Map.entry("maxTimeReverse", 15));
        }

        @Test
        @DisplayName("a port whose pin is null for a pin kind is reported by the validator")
        void pinMissing() {
            List<ShowPort> ports = new ArrayList<>(Belen2026Fixture.ports());
            ports.set(0, Belen2026Fixture.port(1, "Focos", "relay", Belen2026Fixture.RELAY_TYPE, null, Map.of()));
            Assembled assembled = builder.assemble(Belen2026Fixture.input().withTimeline(null, null)
                    .withNow(() -> "x").withAudioByPiece(null).withLedLengthOf(null).withBoard(null).withMode(null));
            assertThat(assembled.hardware().get("Focos")).containsKey("pin");
            Assembled noPin = builder.assemble(ShowBuildInput.of(Belen2026Fixture.document(), Belen2026Fixture.context(), ports));
            assertThat(noPin.hardware().get("Focos")).doesNotContainKey("pin");
            assertThat(codes(errors(noPin))).contains("E_CONFIG_INVALID");
        }

        @Test
        @DisplayName("an unknown kind is an error and the port is left out")
        void unknownKind() {
            List<ShowPort> ports = new ArrayList<>(Belen2026Fixture.ports());
            ports.add(Belen2026Fixture.port(12, "Laser", "laser", null, 5, Map.of()));
            Assembled assembled = builder.assemble(ShowBuildInput.of(Belen2026Fixture.document(), Belen2026Fixture.context(), ports));
            assertThat(assembled.hardware()).doesNotContainKey("Laser");
            assertThat(codes(errors(assembled))).containsExactly("E_KIND_UNKNOWN");
            assertThat(errors(assembled).get(0).port()).isEqualTo("Laser");
        }
    }

    @Nested
    @DisplayName("pieces sharing a LED strip")
    class Strips {

        static final int STRIP_TYPE = 5;

        private StubSceneContext context() {
            return Belen2026Fixture.context()
                    .piece(9, "Casa simple", STRIP_TYPE, "Tira Led", "led_strip")
                    .piece(10, "Casa doble", STRIP_TYPE, "Tira Led", "led_strip")
                    .action(51, "fill", "Omplir", STRIP_TYPE, "Tira Led", "led_strip",
                            Belen2026Fixture.param("color", AttributeType.TEXT, true, true, null, false),
                            Belen2026Fixture.param("first", AttributeType.INTEGER, false, true, null, false),
                            Belen2026Fixture.param("last", AttributeType.INTEGER, false, true, null, false))
                    .action(52, "animate", "Animació", STRIP_TYPE, "Tira Led", "led_strip",
                            StubSceneContext.select("animation_type", true, List.of("fire", "stars")),
                            Belen2026Fixture.param("duration", AttributeType.DECIMAL, false, true, null, true))
                    .action(53, "clear", "Apagar", STRIP_TYPE, "Tira Led", "led_strip")
                    .action(54, "setBrightness", "Brillantor", STRIP_TYPE, "Tira Led", "led_strip",
                            Belen2026Fixture.param("brightness", AttributeType.INTEGER, true, false, "120", false));
        }

        private List<ShowPort> ports() {
            List<ShowPort> ports = new ArrayList<>(Belen2026Fixture.ports());
            ports.add(Belen2026Fixture.port(12, "Tira 1", "led_strip", STRIP_TYPE, 12, Belen2026Fixture.config("dma", 5, "channel", 0, "length", 44)));
            return ports;
        }

        private final Map<Integer, Integer> lengths = Map.of(9, 16, 10, 28);

        private SceneDocument stripDoc(List<Clip> clips, Map<String, List<String>> stripOrder) {
            List<Item> items = List.of(Belen2026Fixture.item("i-casa1", 9, 12), Belen2026Fixture.item("i-casa2", 10, 12));
            List<Track> tracks = items.stream().map(i -> new Track("t-" + i.id(), i.id(), i.id())).toList();
            return new SceneDocument(1, new SceneDocument.Board(1280, 720, null),
                    List.of(new SceneDocument.Layer("layer-1", "Capa 1", 0, true, false, 1)), items, tracks, clips, 122000, 30, stripOrder, null);
        }

        private Clip k(String id, String item, int action, long t, Map<String, Object> params) {
            return Belen2026Fixture.clip(id, item, action, t, 1000, params);
        }

        private ShowBuildInput input(SceneDocument doc, Map<Integer, Integer> lengthOf) {
            return ShowBuildInput.of(doc, context(), ports()).withLedLengthOf(lengthOf::get);
        }

        @Test
        @DisplayName("turns every piece on the strip into a segment, in order, and targets it with piece-relative LEDs")
        void segments() {
            SceneDocument doc = stripDoc(List.of(
                    k("k1", "i-casa1", 51, 0, Belen2026Fixture.config("color", "#ff0000", "first", 0, "last", 3)),
                    k("k2", "i-casa2", 52, 0, Belen2026Fixture.config("animation_type", "fire")),
                    k("k3", "i-casa1", 53, 5000, Map.of()),
                    k("k4", "i-casa2", 54, 6000, Map.of())), Map.of());
            Assembled assembled = builder.assemble(input(doc, lengths));
            assertThat(errors(assembled)).isEmpty();
            assertThat(assembled.hardware().get("Casa simple")).containsExactly(Map.entry("kind", "led_segment"),
                    Map.entry("strip", "Tira 1"), Map.entry("first", 0), Map.entry("last", 15));
            assertThat(assembled.hardware().get("Casa doble")).containsExactly(Map.entry("kind", "led_segment"),
                    Map.entry("strip", "Tira 1"), Map.entry("first", 16), Map.entry("last", 43));
            assertThat(assembled.timeline().stream().map(e -> e.target() + "." + e.action()).toList())
                    .containsExactly("Casa simple.fill", "Casa doble.animate", "Casa simple.clear", "Tira 1.setBrightness");
            assertThat(assembled.timeline().get(0).params()).containsExactly(Map.entry("color", List.of(255, 0, 0)),
                    Map.entry("first", 0L), Map.entry("last", 3L));
            assertThat(assembled.timeline().get(3).params()).containsExactly(Map.entry("brightness", 120L));
            // Both pieces animate at once on the same strip without an overlap warning: different targets.
            assertThat(codes(assembled.issues())).doesNotContain("W_OVERLAP_CONTINUOUS");
        }

        @Test
        @DisplayName("follows the saved strip order")
        void savedOrder() {
            SceneDocument doc = stripDoc(List.of(k("k1", "i-casa1", 53, 0, Map.of())), Map.of("12", List.of("i-casa2", "i-casa1")));
            Assembled assembled = builder.assemble(input(doc, lengths));
            assertThat(assembled.hardware().get("Casa doble")).contains(Map.entry("first", 0), Map.entry("last", 27));
            assertThat(assembled.hardware().get("Casa simple")).contains(Map.entry("first", 28), Map.entry("last", 43));
        }

        @Test
        @DisplayName("a piece without length takes the rest of the strip only when it is the last one")
        void noLength() {
            Map<Integer, Integer> noLength = Map.of(9, 16);
            Assembled last = builder.assemble(input(stripDoc(List.of(k("k1", "i-casa2", 53, 0, Map.of())), Map.of()), noLength));
            assertThat(errors(last)).isEmpty();
            assertThat(last.hardware().get("Casa doble")).contains(Map.entry("first", 16), Map.entry("last", 43));
            Assembled notLast = builder.assemble(input(stripDoc(List.of(k("k1", "i-casa2", 53, 0, Map.of())),
                    Map.of("12", List.of("i-casa2", "i-casa1"))), noLength));
            // The length-less piece swallows the strip, so the explicit one behind it also falls off the end.
            assertThat(codes(errors(notLast))).containsExactly("E_LED_LENGTH", "E_SEGMENT_RANGE");
            assertThat(errors(notLast).get(0).message()).contains("Casa doble").contains("no és l'última peça");
        }

        @Test
        @DisplayName("a piece beyond the end of the strip has no LEDs")
        void beyondStrip() {
            Map<Integer, Integer> huge = Map.of(9, 44, 10, 10);
            Assembled assembled = builder.assemble(input(stripDoc(List.of(k("k1", "i-casa2", 53, 0, Map.of())), Map.of()), huge));
            assertThat(codes(errors(assembled))).contains("E_SEGMENT_RANGE");
            Map<Integer, Integer> noRoom = Map.of(9, 44);
            Assembled empty = builder.assemble(input(stripDoc(List.of(k("k1", "i-casa2", 53, 0, Map.of())), Map.of()), noRoom));
            assertThat(codes(errors(empty))).containsExactly("E_LED_LENGTH");
            assertThat(errors(empty).get(0).message()).contains("no té cap LED").contains("i no té atribut de longitud");
        }

        @Test
        @DisplayName("reports LEDs beyond the piece or pieces beyond the strip")
        void ranges() {
            Assembled outOfPiece = builder.assemble(input(stripDoc(List.of(
                    k("k1", "i-casa1", 51, 0, Belen2026Fixture.config("color", "#fff", "first", 0, "last", 20))), Map.of()), lengths));
            ShowIssue range = outOfPiece.issues().stream().filter(i -> i.code().equals("E_SEGMENT_RANGE")).findFirst().orElseThrow();
            assertThat(range.message()).contains("Casa simple").contains("0–15");
            Assembled overflow = builder.assemble(input(stripDoc(List.of(k("k1", "i-casa2", 53, 0, Map.of())), Map.of()), Map.of(9, 16, 10, 40)));
            assertThat(codes(errors(overflow))).contains("E_SEGMENT_RANGE");
        }

        @Test
        @DisplayName("keeps segment names unique against ports and other pieces")
        void uniqueNames() {
            StubSceneContext clash = context().piece(9, "Focos", STRIP_TYPE, "Tira Led", "led_strip");
            SceneDocument doc = stripDoc(List.of(k("k1", "i-casa1", 53, 0, Map.of())), Map.of());
            Assembled assembled = builder.assemble(ShowBuildInput.of(doc, clash, ports()).withLedLengthOf(lengths::get));
            assertThat(assembled.hardware().get("Focos")).contains(Map.entry("kind", "relay"));
            assertThat(assembled.hardware().get("Focos #2")).contains(Map.entry("kind", "led_segment"), Map.entry("first", 0), Map.entry("last", 15));
            assertThat(assembled.timeline().get(0).target()).isEqualTo("Focos #2");
        }

        @Test
        @DisplayName("without a length function pieces on a strip have no explicit length")
        void noLengthFunction() {
            Assembled assembled = builder.assemble(ShowBuildInput.of(stripDoc(List.of(k("k1", "i-casa2", 53, 0, Map.of())),
                    Map.of()), context(), ports()));
            // Neither piece has a length: the first is not last, the second gets nothing.
            assertThat(codes(errors(assembled))).containsExactly("E_LED_LENGTH", "E_LED_LENGTH");
            assertThat(errors(assembled).get(1).message()).contains("no té cap LED");
        }
    }

    @Nested
    @DisplayName("events")
    class Events {

        @Test
        @DisplayName("emits one event per port of the item that belongs to the action's piece type, in port order")
        void onePerPort() {
            List<Item> items = items();
            replace(items, i -> i.id().equals("i-casasimple"), i -> withPorts(i, 8, 9));
            Assembled assembled = builder.assemble(Belen2026Fixture.input(doc(items, clips())));
            assertThat(errors(assembled)).isEmpty();
            List<ShowEvent> fills = assembled.timeline().stream()
                    .filter(e -> e.t() == 0 && e.action().equals("fill") && (e.target().equals("CasaSimple") || e.target().equals("CasaDoble")))
                    .toList();
            assertThat(fills.stream().map(ShowEvent::target).toList()).containsExactly("CasaSimple", "CasaDoble", "CasaDoble");
            assertThat(fills.get(0).params()).isEqualTo(fills.get(1).params());
        }

        @Test
        @DisplayName("ignores ports of another piece type on the same item and fails when none matches")
        void otherTypePorts() {
            List<Item> items = items();
            replace(items, i -> i.id().equals("i-focos"), i -> withPorts(i, 1, 6));
            Assembled ok = builder.assemble(Belen2026Fixture.input(doc(items, clips())));
            assertThat(ok.timeline().stream().filter(e -> e.target().equals("Fondo1") && e.action().equals("turn_on"))).isEmpty();
            assertThat(errors(ok)).isEmpty();
            replace(items, i -> i.id().equals("i-focos"), i -> withPorts(i, 6));
            Assembled bad = builder.assemble(Belen2026Fixture.input(doc(items, clips())));
            assertThat(codes(errors(bad))).contains("E_NO_PORT");
            assertThat(errors(bad).get(0).message()).contains("Focos").contains("«Encendre»");
            assertThat(errors(bad).get(0).clipId()).isEqualTo("c01");
        }

        @Test
        @DisplayName("returns no package while there are errors, one per clip, deduplicated")
        void noPackageOnErrors() {
            List<Item> items = items();
            replace(items, i -> i.id().equals("i-focos"), i -> withPorts(i));
            ShowBuildResult result = builder.build(Belen2026Fixture.input(doc(items, clips())));
            assertThat(result.pkg()).isNull();
            assertThat(result.ok()).isFalse();
            assertThat(codes(result.errors())).containsExactly("E_NO_PORT", "E_NO_PORT", "E_NO_PORT");
            assertThat(result.errors()).allMatch(e -> e.message().contains("Focos"));
        }

        @Test
        @DisplayName("sorts by t and keeps clip order on ties")
        void sorted() {
            List<Clip> reversed = clips();
            Collections.reverse(reversed);
            Assembled assembled = builder.assemble(Belen2026Fixture.input(doc(items(), reversed)));
            List<Long> ts = assembled.timeline().stream().map(ShowEvent::t).toList();
            assertThat(ts).isSorted();
            List<String> atEnd = assembled.timeline().stream().filter(e -> e.t() == 118000).map(e -> e.target() + "." + e.action()).toList();
            assertThat(atEnd.get(0)).isEqualTo("Fondo2.animate");
            assertThat(atEnd.get(atEnd.size() - 1)).isEqualTo("Sembra.turn_off");
        }

        @Test
        @DisplayName("maps the clip length to the catalog duration parameter in seconds, fractions kept")
        void durationParam() {
            Assembled assembled = builder.assemble(Belen2026Fixture.input());
            assertThat(find(assembled.timeline(), "LlumsE", "turn_on").params()).containsExactly(Map.entry("time", 21L));
            assertThat(find(assembled.timeline(), "Fondo1", 25000).params().get("duration")).isEqualTo(10L);
            assertThat(find(assembled.timeline(), "Sembra", 7000).params()).isEmpty();
            List<Clip> clips = clips();
            replace(clips, c -> c.id().equals("c15"), c -> withTiming(c, 30000, 2500));
            Assembled fraction = builder.assemble(Belen2026Fixture.input(doc(items(), clips)));
            assertThat(find(fraction.timeline(), "LlumsE", "turn_on").params().get("time")).isEqualTo(2.5);
            assertThat(ShowCanonicalJson.canonical(find(fraction.timeline(), "LlumsE", "turn_on").params())).isEqualTo("{\"time\":2.5}");
        }

        @Test
        @DisplayName("coerces colours from every notation and omits blank optionals")
        void coercesColours() {
            List<Clip> clips = clips();
            replace(clips, c -> c.id().equals("c12"), c -> withParams(c, Belen2026Fixture.config("color", "#0a003c", "from_color", "")));
            Assembled assembled = builder.assemble(Belen2026Fixture.input(doc(items(), clips)));
            ShowEvent ev = find(assembled.timeline(), "Fondo1", 25000);
            assertThat(ev.params()).containsExactly(Map.entry("animation_type", "fade"), Map.entry("color", List.of(10, 0, 60)),
                    Map.entry("duration", 10L));
            assertThat(codes(assembled.issues())).contains("W_FADE_FROM_BLACK");
        }

        @Test
        @DisplayName("reports a missing required parameter and a wrong type with the clip id")
        void requiredAndType() {
            List<Clip> clips = clips();
            replace(clips, c -> c.id().equals("c02"), c -> withParams(c, Map.of()));
            replace(clips, c -> c.id().equals("c03"), c -> withParams(c, Map.of("color", "vermell")));
            Assembled assembled = builder.assemble(Belen2026Fixture.input(doc(items(), clips)));
            List<ShowIssue> errors = errors(assembled);
            assertThat(codes(errors)).containsExactly("E_PARAM_REQUIRED", "E_PARAM_TYPE");
            assertThat(errors.get(0).clipId()).isEqualTo("c02");
            assertThat(errors.get(0).message()).contains("«color»").contains("Omplir");
            assertThat(errors.get(1).message()).contains("color").contains("(valor: \"vermell\")");
            assertThat(errors.get(1).clipId()).isEqualTo("c03");
            assertThat(errors.get(1).port()).isEqualTo("Fondo2");
        }

        @Test
        @DisplayName("ignores unset or leftover parameters of other animations and rejects an unknown parameter")
        void animationParams() {
            StubSceneContext ctx = Belen2026Fixture.context()
                    .action(22, "animate", "Fos", Belen2026Fixture.SEGMENT_TYPE, "Tipus 2", "led_segment",
                            Belen2026Fixture.selectStatic("animation_type", "fade", List.of("fade")),
                            Belen2026Fixture.param("color", AttributeType.TEXT, true, true, null, false),
                            Belen2026Fixture.param("from_color", AttributeType.TEXT, false, true, null, false),
                            Belen2026Fixture.param("duration", AttributeType.DECIMAL, false, true, null, true),
                            Belen2026Fixture.param("intensity", AttributeType.DECIMAL, false, true, null, false));
            List<Clip> clips = clips();
            replace(clips, c -> c.id().equals("c12"), c -> withParams(c, Belen2026Fixture.config("color", "[1,2,3]", "from_color", "[0,0,0]")));
            Assembled clean = builder.assemble(ShowBuildInput.of(doc(items(), clips), ctx, Belen2026Fixture.ports()));
            assertThat(errors(clean)).isEmpty();
            assertThat(find(clean.timeline(), "Fondo1", 25000).params()).doesNotContainKey("intensity");

            replace(clips, c -> c.id().equals("c12"), c -> withParams(c, Belen2026Fixture.config("color", "[1,2,3]", "from_color", "[0,0,0]", "intensity", 0.5)));
            Assembled leftover = builder.assemble(ShowBuildInput.of(doc(items(), clips), ctx, Belen2026Fixture.ports()));
            assertThat(errors(leftover)).isEmpty();
            assertThat(codes(warnings(leftover))).contains("W_ANIMATION_PARAM_IGNORED");
            assertThat(find(leftover.timeline(), "Fondo1", 25000).params()).doesNotContainKey("intensity");

            StubSceneContext ctx2 = Belen2026Fixture.context()
                    .action(31, "play", "Reproduir", Belen2026Fixture.AUDIO_TYPE, "Tipus 3", "audio",
                            Belen2026Fixture.param("audio_name", AttributeType.TEXT, true, true, null, false),
                            Belen2026Fixture.param("volume", AttributeType.INTEGER, false, true, null, false));
            List<Clip> clips2 = clips();
            replace(clips2, c -> c.id().equals("c06"), c -> withParams(c, Belen2026Fixture.config("audio_name", "nature.mp3", "volume", 3)));
            Assembled unknown = builder.assemble(ShowBuildInput.of(doc(items(), clips2), ctx2, Belen2026Fixture.ports()));
            assertThat(codes(errors(unknown))).contains("E_PARAM_UNKNOWN");
        }

        @Test
        @DisplayName("a parameter unknown to the chosen animation is E_ANIMATION_PARAM")
        void animationParamUnknown() {
            StubSceneContext ctx = Belen2026Fixture.context()
                    .action(22, "animate", "Fos", Belen2026Fixture.SEGMENT_TYPE, "Tipus 2", "led_segment",
                            Belen2026Fixture.selectStatic("animation_type", "fade", List.of("fade")),
                            Belen2026Fixture.param("color", AttributeType.TEXT, true, true, null, false),
                            Belen2026Fixture.param("glitter", AttributeType.DECIMAL, false, true, null, false));
            List<Clip> clips = clips();
            replace(clips, c -> c.id().equals("c12"), c -> withParams(c, Belen2026Fixture.config("color", "[1,2,3]", "glitter", 1)));
            Assembled assembled = builder.assemble(ShowBuildInput.of(doc(items(), clips), ctx, Belen2026Fixture.ports()));
            assertThat(codes(errors(assembled))).contains("E_ANIMATION_PARAM");
        }

        @Test
        @DisplayName("an enum value outside the catalog list and a number out of range are errors")
        void enumAndRange() {
            StubSceneContext ctx = Belen2026Fixture.context()
                    .action(22, "animate", "Fos", Belen2026Fixture.SEGMENT_TYPE, "Tipus 2", "led_segment",
                            StubSceneContext.select("animation_type", true, List.of("fade", "snow")),
                            Belen2026Fixture.param("color", AttributeType.TEXT, false, true, null, false),
                            Belen2026Fixture.param("first", AttributeType.INTEGER, false, true, null, false));
            List<Clip> clips = clips();
            replace(clips, c -> c.id().equals("c12"), c -> withParams(c, Belen2026Fixture.config("animation_type", "disco", "first", -2)));
            Assembled assembled = builder.assemble(ShowBuildInput.of(doc(items(), clips), ctx, Belen2026Fixture.ports()));
            // The other animate clips now miss the (dynamic) animation_type; look at c12 only.
            List<ShowIssue> c12 = errors(assembled).stream().filter(i -> "c12".equals(i.clipId())).toList();
            assertThat(codes(c12)).containsExactly("E_PARAM_ENUM", "E_PARAM_RANGE");
            assertThat(c12.get(0).message()).contains("fade").contains("snow").contains("(valor: \"disco\")");
            assertThat(c12.get(1).message()).contains("entre 0 i ∞");
            assertThat(codes(errors(assembled))).contains("E_PARAM_REQUIRED");
        }

        @Test
        @DisplayName("an action the port's kind does not know is E_ACTION_UNKNOWN")
        void actionUnknown() {
            StubSceneContext ctx = Belen2026Fixture.context()
                    .action(13, "fill", "Omplir", Belen2026Fixture.RELAY_TYPE, "Tipus 1", "relay");
            Assembled assembled = builder.assemble(ShowBuildInput.of(Belen2026Fixture.document(), ctx, Belen2026Fixture.ports()));
            assertThat(codes(errors(assembled))).containsOnly("E_ACTION_UNKNOWN");
            assertThat(errors(assembled).get(0).message()).contains("«fill»").contains("relay").contains("Focos");
        }

        @Test
        @DisplayName("fails on a stale port and a clip after the end, warns on unused piece ports")
        void staleAndLate() {
            List<Item> items = items();
            replace(items, i -> i.id().equals("i-sembra"), i -> withPorts(i, 2, 999));
            List<Clip> clips = clips();
            replace(clips, c -> c.id().equals("c07"), c -> withTiming(c, 130000, 1000));
            List<ShowPort> ports = new ArrayList<>(Belen2026Fixture.ports());
            ports.add(Belen2026Fixture.port(50, "Pastoret", "relay", Belen2026Fixture.RELAY_TYPE, 22, Map.of()));
            Assembled assembled = builder.assemble(Belen2026Fixture.input(doc(items, clips)).withAudioByPiece(null)
                    .withTimeline(null, null));
            Assembled withPorts = builder.assemble(ShowBuildInput.of(doc(items, clips), Belen2026Fixture.context(), ports));
            assertThat(codes(errors(withPorts))).contains("E_PORT_STALE", "E_T_AFTER_END");
            assertThat(codes(warnings(withPorts))).contains("W_PORT_UNUSED");
            assertThat(codes(errors(assembled))).contains("E_PORT_STALE");
        }

        @Test
        @DisplayName("warns when continuous animations overlap on the same target")
        void overlap() {
            List<Clip> clips = clips();
            replace(clips, c -> c.id().equals("c12"), c -> withTiming(c, 25000, 100000));
            Assembled assembled = builder.assemble(Belen2026Fixture.input(doc(items(), clips)));
            assertThat(assembled.issues().stream().filter(i -> i.code().equals("W_OVERLAP_CONTINUOUS")).map(ShowIssue::port).toList())
                    .containsExactly("Fondo1");
        }

        @Test
        @DisplayName("keeps a clip without action or with an unresolved action as warnings only")
        void noAction() {
            List<Clip> clips = clips();
            replace(clips, c -> c.id().equals("c08"), c -> withAction(c, null));
            replace(clips, c -> c.id().equals("c09"), c -> withAction(c, 777));
            Assembled assembled = builder.assemble(Belen2026Fixture.input(doc(items(), clips)));
            assertThat(errors(assembled)).isEmpty();
            assertThat(codes(assembled.issues())).contains("W_CLIP_NO_ACTION", "W_ACTION_UNRESOLVED");
            assertThat(assembled.issues().stream().filter(i -> i.code().equals("W_CLIP_NO_ACTION")).findFirst().get().clipId()).isEqualTo("c08");
        }

        @Test
        @DisplayName("a clip on a missing track and an item whose piece is gone are warnings")
        void missingTrackAndPiece() {
            List<Clip> clips = clips();
            clips.add(new Clip("c99", "t-nope", 11, 0, 1000, Map.of(), "Orfe"));
            List<Item> items = items();
            items.add(Belen2026Fixture.item("i-ghost", 999, 1));
            Assembled assembled = builder.assemble(Belen2026Fixture.input(doc(items, clips)));
            assertThat(codes(warnings(assembled))).contains("W_TRACK_MISSING", "W_PIECE_MISSING");
            assertThat(assembled.issues().stream().filter(i -> i.code().equals("W_TRACK_MISSING")).findFirst().get().message())
                    .contains("Orfe");
            assertThat(errors(assembled)).isEmpty();
        }

        @Test
        @DisplayName("a double pulse exports its three static durations as numbers, ignoring the clip length")
        void doublePulseStatics() {
            StubSceneContext ctx = Belen2026Fixture.context()
                    .action(13, "double_pulse", "Doble pulsació", Belen2026Fixture.RELAY_TYPE, "Tipus 1", "relay",
                            Belen2026Fixture.param("first_press", AttributeType.DECIMAL, true, false, "0.3", false),
                            Belen2026Fixture.param("gap", AttributeType.DECIMAL, true, false, "0.2", false),
                            Belen2026Fixture.param("second_press", AttributeType.DECIMAL, true, false, "5", false));
            Assembled assembled = builder.assemble(ShowBuildInput.of(Belen2026Fixture.document(), ctx, Belen2026Fixture.ports()));
            assertThat(errors(assembled)).isEmpty();
            assertThat(find(assembled.timeline(), "Sembra", 7000).action()).isEqualTo("double_pulse");
            assertThat(find(assembled.timeline(), "Sembra", 7000).params())
                    .containsExactly(Map.entry("first_press", 0.3), Map.entry("gap", 0.2), Map.entry("second_press", 5L));
            assertThat(ShowCanonicalJson.canonical(find(assembled.timeline(), "Sembra", 7000).params()))
                    .isEqualTo("{\"first_press\":0.3,\"gap\":0.2,\"second_press\":5}");
        }

        @Test
        @DisplayName("a double pulse without one of its static values is E_PARAM_REQUIRED")
        void doublePulseMissingStatic() {
            StubSceneContext ctx = Belen2026Fixture.context()
                    .action(13, "double_pulse", "Doble pulsació", Belen2026Fixture.RELAY_TYPE, "Tipus 1", "relay",
                            Belen2026Fixture.param("first_press", AttributeType.DECIMAL, true, false, "0.3", false),
                            Belen2026Fixture.param("gap", AttributeType.DECIMAL, true, false, "", false),
                            Belen2026Fixture.param("second_press", AttributeType.DECIMAL, true, false, "0.3", false));
            Assembled assembled = builder.assemble(ShowBuildInput.of(Belen2026Fixture.document(), ctx, Belen2026Fixture.ports()));
            assertThat(codes(errors(assembled))).contains("E_PARAM_REQUIRED");
            assertThat(errors(assembled)).anySatisfy(e -> assertThat(e.message()).contains("«gap»"));
        }

        @Test
        @DisplayName("parses static values by type and uses the clip label in messages")
        void staticValues() {
            StubSceneContext ctx = Belen2026Fixture.context()
                    .action(13, "turn_on", "Encendre", Belen2026Fixture.RELAY_TYPE, "Tipus 1", "relay",
                            Belen2026Fixture.param("time", AttributeType.DECIMAL, false, false, "1.5", false))
                    .action(14, "turn_off", "Apagar", Belen2026Fixture.RELAY_TYPE, "Tipus 1", "relay",
                            Belen2026Fixture.param("time", AttributeType.INTEGER, false, false, "", false));
            Assembled assembled = builder.assemble(ShowBuildInput.of(Belen2026Fixture.document(), ctx, Belen2026Fixture.ports()));
            assertThat(find(assembled.timeline(), "Sembra", 7000).params()).containsExactly(Map.entry("time", 1.5));
            assertThat(find(assembled.timeline(), "Sembra", 23000).params()).isEmpty();
            Map<String, Object> label = new LinkedHashMap<>();
            label.put("color", "nope");
            List<Clip> clips = clips();
            replace(clips, c -> c.id().equals("c02"), c -> new Clip(c.id(), c.trackId(), c.pieceTypeActionId(), c.startMs(), c.durationMs(), label, "Fons taronja"));
            Assembled labelled = builder.assemble(Belen2026Fixture.input(doc(items(), clips)));
            assertThat(errors(labelled).get(0).message()).contains("«Fons taronja»");
        }
    }

    @Nested
    @DisplayName("audio assets")
    class Audio {

        private final ShowAudioFile nature = new ShowAudioFile(12, "nature.mp3", "audio/mpeg", 2048, 61000L);

        private StubSceneContext trimContext() {
            return Belen2026Fixture.context()
                    .action(31, "play", "Reproduir", Belen2026Fixture.AUDIO_TYPE, "Tipus 3", "audio",
                            Belen2026Fixture.param("audio_name", AttributeType.TEXT, true, true, null, false),
                            Belen2026Fixture.param("start", AttributeType.DECIMAL, false, true, null, false),
                            Belen2026Fixture.param("time", AttributeType.DECIMAL, false, true, null, true));
        }

        private List<Clip> clipsWithAudio(Map<String, Object> params, long durationMs) {
            List<Clip> clips = clips();
            replace(clips, c -> c.id().equals("c06"), c -> withTiming(withParams(c, params), 0, durationMs));
            return clips;
        }

        @Test
        @DisplayName("collects audio assets without duplicates")
        void collects() {
            List<Clip> clips = clips();
            clips.add(Belen2026Fixture.clip("c99", "i-audio", 31, 60000, 1000, Map.of("audio_name", "nature.mp3")));
            clips.add(Belen2026Fixture.clip("c98", "i-audio", 31, 61000, 1000, Map.of("audio_name", "night.mp3")));
            Assembled assembled = builder.assemble(Belen2026Fixture.input(doc(items(), clips)));
            assertThat(assembled.assets().audio()).containsExactly("nature.mp3", "night.mp3");
        }

        @Test
        @DisplayName("exports start and the clip length as time, in seconds")
        void startAndTime() {
            SceneDocument doc = doc(items(), clipsWithAudio(Belen2026Fixture.config("audio_name", "12-nature.mp3", "start", 2.5), 12500));
            Assembled assembled = builder.assemble(ShowBuildInput.of(doc, trimContext(), Belen2026Fixture.ports()));
            assertThat(errors(assembled)).isEmpty();
            assertThat(find(assembled.timeline(), "Audio", "play").params()).containsExactly(Map.entry("audio_name", "12-nature.mp3"),
                    Map.entry("start", 2.5), Map.entry("time", 12.5));
            assertThat(assembled.assets().audio()).containsExactly("12-nature.mp3");
        }

        @Test
        @DisplayName("lists where each file comes from in meta.assets when the files are known; same hash either way")
        void metaAssets() {
            SceneDocument doc = doc(items(), clipsWithAudio(Map.of("audio_name", "12-nature.mp3"), 1000));
            ShowBuildResult result = builder.build(Belen2026Fixture.input(doc).withAudioByPiece(Map.of(8, List.of(nature))));
            assertThat(result.errors()).isEmpty();
            assertThat(result.pkg().meta().assets()).containsExactly(
                    new ShowAssetRef("12-nature.mp3", "audio", 8, 12, "audio/mpeg", 2048, 61000L));
            ShowBuildResult plain = builder.build(Belen2026Fixture.input(doc));
            assertThat(plain.pkg().meta().assets()).isNull();
            assertThat(plain.pkg().meta().hash()).isEqualTo(result.pkg().meta().hash());
            assertThat(ShowCanonicalJson.pretty(plain.pkg())).doesNotContain("\"assets\": [");
        }

        @Test
        @DisplayName("refuses a file that is not among the piece's audios once they are loaded, and ignores unloaded pieces")
        void assetMissing() {
            SceneDocument doc = doc(items(), clipsWithAudio(Map.of("audio_name", "99-gone.mp3"), 1000));
            Assembled loaded = builder.assemble(Belen2026Fixture.input(doc).withAudioByPiece(Map.of(8, List.of(nature))));
            assertThat(codes(errors(loaded))).contains("E_ASSET_MISSING");
            assertThat(errors(loaded).get(0).message()).contains("«99-gone.mp3»").contains("«Audio»");
            assertThat(loaded.timeline().stream().anyMatch(e -> e.action().equals("play"))).isFalse();
            Assembled notLoaded = builder.assemble(Belen2026Fixture.input(doc).withAudioByPiece(Map.of()));
            assertThat(codes(errors(notLoaded))).doesNotContain("E_ASSET_MISSING");
            assertThat(notLoaded.assetRefs()).isEmpty();
            // A piece with an empty list of files is loaded: every name is missing.
            Assembled empty = builder.assemble(Belen2026Fixture.input(doc).withAudioByPiece(Map.of(8, List.of())));
            assertThat(codes(errors(empty))).contains("E_ASSET_MISSING");
        }

        @Test
        @DisplayName("warns when the clip runs past the end of the file")
        void beyondEnd() {
            SceneDocument late = doc(items(), clipsWithAudio(Belen2026Fixture.config("audio_name", "12-nature.mp3", "start", 50), 20000));
            Assembled assembled = builder.assemble(ShowBuildInput.of(late, trimContext(), Belen2026Fixture.ports())
                    .withAudioByPiece(Map.of(8, List.of(nature))));
            ShowIssue w = assembled.issues().stream().filter(i -> i.code().equals("W_AUDIO_BEYOND_END")).findFirst().orElseThrow();
            assertThat(w.isError()).isFalse();
            assertThat(w.message()).contains("9.0 s").contains("nature.mp3").contains("1:01");
            SceneDocument fits = doc(items(), clipsWithAudio(Belen2026Fixture.config("audio_name", "12-nature.mp3", "start", 50), 11000));
            Assembled ok = builder.assemble(ShowBuildInput.of(fits, trimContext(), Belen2026Fixture.ports())
                    .withAudioByPiece(Map.of(8, List.of(nature))));
            assertThat(codes(ok.issues())).doesNotContain("W_AUDIO_BEYOND_END");
        }

        @Test
        @DisplayName("a negative start is rejected by the catalog minimum")
        void negativeStart() {
            SceneDocument doc = doc(items(), clipsWithAudio(Belen2026Fixture.config("audio_name", "12-nature.mp3", "start", -1), 1000));
            Assembled assembled = builder.assemble(ShowBuildInput.of(doc, trimContext(), Belen2026Fixture.ports()));
            assertThat(codes(errors(assembled))).contains("E_PARAM_RANGE");
        }
    }
}
