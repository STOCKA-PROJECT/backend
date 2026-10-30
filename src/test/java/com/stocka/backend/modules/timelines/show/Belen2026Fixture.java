package com.stocka.backend.modules.timelines.show;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.stocka.backend.modules.piecetypes.dto.ActionParameterDto;
import com.stocka.backend.modules.piecetypes.dto.AttributeValidatorsDto;
import com.stocka.backend.modules.piecetypes.entity.AttributeType;
import com.stocka.backend.modules.timelines.scene.SceneDocument;
import com.stocka.backend.modules.timelines.scene.SceneDocument.Board;
import com.stocka.backend.modules.timelines.scene.SceneDocument.Clip;
import com.stocka.backend.modules.timelines.scene.SceneDocument.Item;
import com.stocka.backend.modules.timelines.scene.SceneDocument.Layer;
import com.stocka.backend.modules.timelines.scene.SceneDocument.Track;
import com.stocka.backend.modules.timelines.scene.StubSceneContext;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The same slice of the 2025-26 show that {@code rpi4-system/contract/samples/belen-2026.show.json}
 * holds, expressed as a scene plus the organization's catalog: a 1:1 port of the editor's
 * {@code utils/__tests__/fixtures/belen2026.ts}. Exporting it must produce that file byte for byte
 * ({@code meta.exportedAt} fixed), hash included.
 */
public final class Belen2026Fixture {

    public static final int RELAY_TYPE = 1;
    public static final int SEGMENT_TYPE = 2;
    public static final int AUDIO_TYPE = 3;
    public static final int TIMED_RELAY_TYPE = 4;

    public static final String TIMELINE_NAME = "Pessebre 2026 (mostra)";
    public static final String EXPORTED_AT = "2026-10-12T00:00:00.000Z";
    public static final String HASH = "sha256:88db057d40d0926dab27200759f898fddc5161abc9104f84b192984fa9cb4ce5";
    public static final String SAMPLE_RESOURCE = "/show/belen-2026.show.json";

    private Belen2026Fixture() {
    }

    /** @return the organization's ports, in position order */
    public static List<ShowPort> ports() {
        List<ShowPort> out = new ArrayList<>();
        out.add(port(1, "Focos", "relay", RELAY_TYPE, 27, Map.of()));
        out.add(port(2, "Sembra", "relay", RELAY_TYPE, 26, Map.of()));
        out.add(port(3, "LlumsE", "relay", TIMED_RELAY_TYPE, 18, Map.of()));
        out.add(port(4, "LedStrip3", "led_strip", null, 13, config("dma", 10, "channel", 1, "length", 300)));
        out.add(port(5, "LedStrip4", "led_strip", null, 10, config("dma", 8, "channel", 0, "length", 44)));
        out.add(port(6, "Fondo1", "led_segment", SEGMENT_TYPE, null, config("strip", "LedStrip3", "first", 0, "last", 148)));
        out.add(port(7, "Fondo2", "led_segment", SEGMENT_TYPE, null, config("strip", "LedStrip3", "first", 298, "last", 149)));
        out.add(port(8, "CasaSimple", "led_segment", SEGMENT_TYPE, null, config("strip", "LedStrip4", "first", 0, "last", 15)));
        out.add(port(9, "CasaDoble", "led_segment", SEGMENT_TYPE, null, config("strip", "LedStrip4", "first", 16, "last", 43)));
        out.add(port(10, "Audio", "audio", AUDIO_TYPE, null, Map.of()));
        out.add(port(11, "Pulsador Belen", "sensor", null, 20, config("onActive", "play")));
        return out;
    }

    /** @return pieces, ports and actions of the organization */
    public static StubSceneContext context() {
        StubSceneContext ctx = new StubSceneContext()
                .piece(1, "Focos", RELAY_TYPE, "Tipus 1", "relay")
                .piece(2, "Sembra", RELAY_TYPE, "Tipus 1", "relay")
                .piece(3, "LlumsE", TIMED_RELAY_TYPE, "Tipus 4", "relay")
                .piece(4, "Fondo1", SEGMENT_TYPE, "Tipus 2", "led_segment")
                .piece(5, "Fondo2", SEGMENT_TYPE, "Tipus 2", "led_segment")
                .piece(6, "CasaSimple", SEGMENT_TYPE, "Tipus 2", "led_segment")
                .piece(7, "CasaDoble", SEGMENT_TYPE, "Tipus 2", "led_segment")
                .piece(8, "Audio", AUDIO_TYPE, "Tipus 3", "audio");
        for (ShowPort p : ports()) {
            ctx.port(p.id(), p.name(), p.pieceTypeId(), p.kind());
        }
        ctx.action(13, "turn_on", "Encendre", RELAY_TYPE, "Tipus 1", "relay")
                .action(14, "turn_off", "Apagar", RELAY_TYPE, "Tipus 1", "relay")
                .action(41, "turn_on", "Encendre un temps", TIMED_RELAY_TYPE, "Tipus 4", "relay",
                        param("time", AttributeType.DECIMAL, false, true, null, true))
                .action(42, "turn_off", "Apagar", TIMED_RELAY_TYPE, "Tipus 4", "relay")
                .action(21, "fill", "Omplir", SEGMENT_TYPE, "Tipus 2", "led_segment",
                        param("color", AttributeType.TEXT, true, true, null, false))
                .action(22, "animate", "Fos", SEGMENT_TYPE, "Tipus 2", "led_segment",
                        selectStatic("animation_type", "fade", List.of("fade")),
                        param("color", AttributeType.TEXT, true, true, null, false),
                        param("from_color", AttributeType.TEXT, false, true, null, false),
                        param("duration", AttributeType.DECIMAL, false, true, null, true))
                .action(31, "play", "Reproduir", AUDIO_TYPE, "Tipus 3", "audio",
                        param("audio_name", AttributeType.TEXT, true, true, null, false))
                .action(32, "stop", "Aturar", AUDIO_TYPE, "Tipus 3", "audio");
        return ctx;
    }

    /** @return items: one per piece, with its port assigned */
    public static List<Item> items() {
        List<Item> out = new ArrayList<>();
        out.add(item("i-focos", 1, 1));
        out.add(item("i-sembra", 2, 2));
        out.add(item("i-llumse", 3, 3));
        out.add(item("i-fondo1", 4, 6));
        out.add(item("i-fondo2", 5, 7));
        out.add(item("i-casasimple", 6, 8));
        out.add(item("i-casadoble", 7, 9));
        out.add(item("i-audio", 8, 10));
        return out;
    }

    /** @return one track per item, {@code t-<itemId>} */
    public static List<Track> tracks() {
        List<Track> out = new ArrayList<>();
        for (Item i : items()) {
            out.add(new Track("t-" + i.id(), i.id(), i.id()));
        }
        return out;
    }

    /** @return clips in the order the sample lists its events, so stable sorting reproduces that order */
    public static List<Clip> clips() {
        List<Clip> out = new ArrayList<>();
        out.add(clip("c01", "i-focos", 13, 0, 1000, Map.of()));
        out.add(clip("c02", "i-fondo1", 21, 0, 1000, Map.of("color", "[255,50,0]")));
        out.add(clip("c03", "i-fondo2", 21, 0, 1000, Map.of("color", "[255,50,0]")));
        out.add(clip("c04", "i-casasimple", 21, 0, 1000, Map.of("color", "[255,50,0]")));
        out.add(clip("c05", "i-casadoble", 21, 0, 1000, Map.of("color", "[50,16,0]")));
        out.add(clip("c06", "i-audio", 31, 0, 1000, Map.of("audio_name", "nature.mp3")));
        out.add(clip("c07", "i-sembra", 13, 7000, 1000, Map.of()));
        out.add(clip("c08", "i-focos", 14, 20000, 1000, Map.of()));
        out.add(clip("c09", "i-sembra", 14, 23000, 1000, Map.of()));
        out.add(clip("c10", "i-casasimple", 22, 24000, 3000, fade("[0,0,0]", "[255,50,0]")));
        out.add(clip("c11", "i-casadoble", 22, 24000, 3000, fade("[0,0,0]", "[50,16,0]")));
        out.add(clip("c12", "i-fondo1", 22, 25000, 10000, fade("[10,0,60]", "[255,50,0]")));
        out.add(clip("c13", "i-fondo2", 22, 25000, 10000, fade("[10,0,60]", "[255,50,0]")));
        out.add(clip("c14", "i-audio", 32, 27000, 1000, Map.of()));
        out.add(clip("c15", "i-llumse", 41, 30000, 21000, Map.of()));
        out.add(clip("c16", "i-sembra", 14, 118000, 1000, Map.of()));
        out.add(clip("c17", "i-audio", 32, 118000, 1000, Map.of()));
        out.add(clip("c18", "i-focos", 14, 118000, 1000, Map.of()));
        out.add(clip("c19", "i-casasimple", 22, 118000, 4000, fade("[0,0,0]", "[255,50,0]")));
        out.add(clip("c20", "i-casadoble", 22, 118000, 4000, fade("[0,0,0]", "[50,16,0]")));
        out.add(clip("c21", "i-fondo1", 22, 118000, 4000, fade("[0,0,0]", "[255,50,0]")));
        out.add(clip("c22", "i-fondo2", 22, 118000, 4000, fade("[0,0,0]", "[255,50,0]")));
        return out;
    }

    /** @return the scene document of the sample */
    public static SceneDocument document() {
        return document(items(), tracks(), clips());
    }

    /** @return a scene document with the given content and the sample's board, layer and length */
    public static SceneDocument document(List<Item> items, List<Track> tracks, List<Clip> clips) {
        return new SceneDocument(1, new Board(1280, 720, null), List.of(new Layer("layer-1", "Capa 1", 0, true, false, 1)),
                items, tracks, clips, 122000, 30, Map.of(), null);
    }

    /** @return the builder input of the sample, without audio files (as the editor's fixture test) */
    public static ShowBuildInput input() {
        return input(document());
    }

    /** @return the builder input of the sample for another document */
    public static ShowBuildInput input(SceneDocument doc) {
        return ShowBuildInput.of(doc, context(), ports()).withTimeline(TIMELINE_NAME, null).withNow(() -> EXPORTED_AT);
    }

    /** @return the sample file as text */
    public static String sampleText() {
        try (InputStream in = Belen2026Fixture.class.getResourceAsStream(SAMPLE_RESOURCE)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** @return the sample file parsed */
    public static JsonNode sample() {
        return JsonMapper.builder().build().readTree(sampleText());
    }

    // ----- builders -----

    public static ShowPort port(int id, String name, String kind, Integer pieceTypeId, Integer pin, Map<String, Object> config) {
        return new ShowPort(id, name, kind, pieceTypeId, pin, config, id);
    }

    public static Item item(String id, int pieceId, Integer... ports) {
        return new Item(id, pieceId, "layer-1", 0, 0, 100, 100, 0, null, null, List.of(ports), null, null);
    }

    public static Clip clip(String id, String itemId, Integer actionId, long startMs, long durationMs, Map<String, Object> params) {
        return new Clip(id, "t-" + itemId, actionId, startMs, durationMs, params, null);
    }

    public static Map<String, Object> fade(String color, String fromColor) {
        return config("color", color, "from_color", fromColor);
    }

    /** @return an ordered map from alternating keys and values */
    public static Map<String, Object> config(Object... kv) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            out.put((String) kv[i], kv[i + 1]);
        }
        return out;
    }

    public static ActionParameterDto param(String name, AttributeType type, boolean required, boolean dynamic,
                                           String staticValue, boolean duration) {
        return StubSceneContext.param(name, type, required, dynamic, staticValue, duration);
    }

    public static ActionParameterDto selectStatic(String name, String staticValue, List<String> options) {
        ActionParameterDto p = param(name, AttributeType.SELECT, true, false, staticValue, false);
        AttributeValidatorsDto v = new AttributeValidatorsDto();
        v.setOptions(options);
        p.setValidators(v);
        return p;
    }
}
