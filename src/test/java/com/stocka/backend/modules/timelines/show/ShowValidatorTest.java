package com.stocka.backend.modules.timelines.show;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.stocka.backend.modules.contract.CatalogParam;
import com.stocka.backend.modules.contract.CatalogType;
import com.stocka.backend.modules.contract.OutputCatalog;
import com.stocka.backend.modules.timelines.show.ShowPackage.ShowAssets;
import com.stocka.backend.modules.timelines.show.ShowPackage.ShowEvent;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("ShowValidator")
class ShowValidatorTest {

    private final OutputCatalog catalog = new OutputCatalog();
    private final Map<String, Map<String, Object>> hardware = new LinkedHashMap<>();
    private final List<ShowEvent> timeline = new ArrayList<>();
    private ShowAssets assets = new ShowAssets(List.of(), List.of());
    private long durationMs = 122000;

    private void port(String name, Object... kv) {
        hardware.put(name, Belen2026Fixture.config(kv));
    }

    private void event(long t, String target, String action, Object... params) {
        timeline.add(new ShowEvent(t, target, action, Belen2026Fixture.config(params)));
    }

    private List<ShowIssue> validate() {
        return ShowValidator.validate(hardware, timeline, "rpi4b", durationMs, assets, catalog);
    }

    private List<String> codes(List<ShowIssue> issues) {
        return issues.stream().map(ShowIssue::code).toList();
    }

    private List<String> errors() {
        return codes(validate().stream().filter(ShowIssue::isError).toList());
    }

    private List<String> warnings() {
        return codes(validate().stream().filter(i -> !i.isError()).toList());
    }

    @Nested
    @DisplayName("the shared sample")
    class Sample {

        @Test
        @DisplayName("validates without errors or warnings")
        void clean() {
            JsonNode sample = Belen2026Fixture.sample();
            JsonMapper json = JsonMapper.builder().build();
            @SuppressWarnings("unchecked")
            Map<String, Map<String, Object>> hw = json.convertValue(sample.get("hardware"), Map.class);
            List<ShowEvent> events = new ArrayList<>();
            for (JsonNode ev : sample.get("timeline")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> params = json.convertValue(ev.get("params"), Map.class);
                events.add(new ShowEvent(ev.get("t").asLong(), ev.get("target").asText(), ev.get("action").asText(), params));
            }
            ShowAssets declared = new ShowAssets(List.of("nature.mp3"), List.of());
            assertThat(ShowValidator.validate(hw, events, "rpi4b", 122000, declared, catalog)).isEmpty();
        }
    }

    @Nested
    @DisplayName("checkValue")
    class CheckValue {

        private CatalogParam spec(CatalogType type, String min, String max, List<String> values) {
            return new CatalogParam("p", type, false, min == null ? null : new BigDecimal(min),
                    max == null ? null : new BigDecimal(max), values, false, null, null, null);
        }

        @Test
        @DisplayName("checks each type strictly, without coercion")
        void types() {
            assertThat(ShowValidator.checkValue(3L, spec(CatalogType.INT, null, null, List.of()))).isNull();
            assertThat(ShowValidator.checkValue(3.0, spec(CatalogType.INT, null, null, List.of()))).isNull();
            assertThat(ShowValidator.checkValue(3.5, spec(CatalogType.INT, null, null, List.of()))).isEqualTo("ha de ser un enter");
            assertThat(ShowValidator.checkValue("3", spec(CatalogType.INT, null, null, List.of()))).isEqualTo("ha de ser un enter");
            assertThat(ShowValidator.checkValue(2.5, spec(CatalogType.NUMBER, null, null, List.of()))).isNull();
            assertThat(ShowValidator.checkValue(true, spec(CatalogType.NUMBER, null, null, List.of()))).isEqualTo("ha de ser un número");
            assertThat(ShowValidator.checkValue(true, spec(CatalogType.BOOL, null, null, List.of()))).isNull();
            assertThat(ShowValidator.checkValue("true", spec(CatalogType.BOOL, null, null, List.of()))).isEqualTo("ha de ser booleà");
            assertThat(ShowValidator.checkValue("x", spec(CatalogType.STRING, null, null, List.of()))).isNull();
            assertThat(ShowValidator.checkValue(1, spec(CatalogType.STRING, null, null, List.of()))).isEqualTo("ha de ser text");
            assertThat(ShowValidator.checkValue(List.of(1, 2, 3), spec(CatalogType.RGB, null, null, List.of()))).isNull();
            assertThat(ShowValidator.checkValue(List.of(1, 2, 300), spec(CatalogType.RGB, null, null, List.of()))).startsWith("ha de ser un color");
            assertThat(ShowValidator.checkValue("#fff", spec(CatalogType.RGB, null, null, List.of()))).startsWith("ha de ser un color");
            assertThat(ShowValidator.checkValue(List.of(List.of(1, 2, 3)), spec(CatalogType.RGB_LIST, null, null, List.of()))).isNull();
            assertThat(ShowValidator.checkValue(List.of(List.of(1, 2)), spec(CatalogType.RGB_LIST, null, null, List.of()))).startsWith("ha de ser una llista");
            assertThat(ShowValidator.checkValue("fade", spec(CatalogType.ENUM, null, null, List.of("fade")))).isNull();
            assertThat(ShowValidator.checkValue("fire", spec(CatalogType.ENUM, null, null, List.of("fade")))).isEqualTo("ha de ser un de: fade");
        }

        @Test
        @DisplayName("checks numeric bounds")
        void bounds() {
            assertThat(ShowValidator.checkValue(-1L, spec(CatalogType.NUMBER, "0", null, List.of()))).isEqualTo("ha de ser com a mínim 0");
            assertThat(ShowValidator.checkValue(300L, spec(CatalogType.INT, "0", "255", List.of()))).isEqualTo("ha de ser com a màxim 255");
            assertThat(ShowValidator.checkValue(100L, spec(CatalogType.INT, "0", "255", List.of()))).isNull();
        }
    }

    @Nested
    @DisplayName("hardware")
    class Hardware {

        @Test
        @DisplayName("an unknown kind is an error and nothing else is checked for it")
        void unknownKind() {
            port("Laser", "kind", "laser", "pin", 5);
            assertThat(errors()).containsExactly("E_KIND_UNKNOWN");
        }

        @Test
        @DisplayName("config keys must be present when required and of the right type")
        void config() {
            port("Tira", "kind", "led_strip", "pin", 13, "dma", 10, "channel", 1);
            assertThat(errors()).containsExactly("E_CONFIG_INVALID");
            assertThat(validate().get(0).message()).contains("necessita «length»");
            hardware.get("Tira").put("length", "molt");
            assertThat(errors()).containsExactly("E_CONFIG_INVALID");
            assertThat(validate().get(0).message()).contains("«length» ha de ser un enter");
        }

        @Test
        @DisplayName("a kind with a pin needs one")
        void pinRequired() {
            port("Focos", "kind", "relay");
            assertThat(errors()).containsExactly("E_CONFIG_INVALID");
            assertThat(validate().get(0).message()).contains("necessita «pin»");
        }

        @Test
        @DisplayName("pins must exist on the board, not be reserved; pull-up pins warn")
        void boardPins() {
            port("A", "kind", "relay", "pin", 40);
            port("B", "kind", "relay", "pin", 0);
            port("C", "kind", "relay", "pin", 4);
            event(0, "A", "turn_on");
            event(0, "B", "turn_on");
            event(0, "C", "turn_on");
            assertThat(errors()).containsExactly("E_PIN_OUT_OF_BOARD", "E_PIN_RESERVED");
            assertThat(warnings()).containsExactly("W_PIN_PULLUP");
        }

        @Test
        @DisplayName("a pin used twice is an error, motor pins included")
        void duplicatePins() {
            port("A", "kind", "relay", "pin", 26);
            port("M", "kind", "motor", "pinR", 26, "pinL", 27, "sensorPin", 25, "downTime", 15);
            event(0, "A", "turn_on");
            event(0, "M", "stop");
            List<ShowIssue> issues = validate();
            assertThat(codes(issues)).containsExactly("E_PIN_DUPLICATE");
            assertThat(issues.get(0).message()).contains("A, M.pinR");
        }

        @Test
        @DisplayName("a motor cannot repeat a pin among its own three")
        void motorPins() {
            port("M", "kind", "motor", "pinR", 26, "pinL", 26, "sensorPin", 25, "downTime", 15);
            event(0, "M", "stop");
            // The repeated pin is also, by construction, a pin used by two outputs of the board.
            assertThat(errors()).containsExactly("E_CONFIG_INVALID", "E_PIN_DUPLICATE");
            assertThat(validate().get(0).message()).contains("repeteix un pin");
        }

        @Test
        @DisplayName("a strip pin must drive WS281x with the matching channel")
        void ws281x() {
            port("T1", "kind", "led_strip", "pin", 27, "dma", 10, "channel", 0, "length", 10);
            port("T2", "kind", "led_strip", "pin", 13, "dma", 10, "channel", 0, "length", 10);
            assertThat(errors()).containsExactly("E_WS281X_PIN", "E_WS281X_CHANNEL");
        }

        @Test
        @DisplayName("the PCM pin warns when the show has audio; avoided DMA channels warn")
        void pcmAndDma() {
            port("T", "kind", "led_strip", "pin", 21, "dma", 2, "channel", 0, "length", 10);
            assertThat(warnings()).containsExactly("W_DMA_AVOID");
            port("Audio", "kind", "audio");
            event(0, "Audio", "stop");
            assertThat(warnings()).containsExactly("W_PCM_AUDIO", "W_DMA_AVOID");
            assertThat(validate().stream().filter(i -> i.code().equals("W_PCM_AUDIO")).findFirst().get().message())
                    .contains("pot interferir");
        }
    }

    @Nested
    @DisplayName("segments")
    class Segments {

        @Test
        @DisplayName("a segment needs its strip to exist as a led_strip")
        void stripMissing() {
            port("Seg", "kind", "led_segment", "strip", "Nope", "first", 0, "last", 3);
            event(0, "Seg", "clear");
            assertThat(errors()).containsExactly("E_SEGMENT_STRIP_MISSING");
            port("Nope", "kind", "relay", "pin", 26);
            event(0, "Nope", "turn_on");
            assertThat(errors()).containsExactly("E_SEGMENT_STRIP_MISSING");
        }

        @Test
        @DisplayName("a segment must fit inside its strip, in either direction")
        void range() {
            port("T", "kind", "led_strip", "pin", 13, "dma", 10, "channel", 1, "length", 44);
            port("Ok", "kind", "led_segment", "strip", "T", "first", 43, "last", 16);
            port("Out", "kind", "led_segment", "strip", "T", "first", 0, "last", 44);
            event(0, "Ok", "clear");
            event(0, "Out", "clear");
            List<ShowIssue> issues = validate();
            assertThat(codes(issues)).containsExactly("E_SEGMENT_RANGE");
            assertThat(issues.get(0).message()).contains("«Out» (0–44)").contains("(0–43)");
        }

        @Test
        @DisplayName("first/last of an event are relative to the segment or the whole strip")
        void eventRange() {
            port("T", "kind", "led_strip", "pin", 13, "dma", 10, "channel", 1, "length", 44);
            port("Seg", "kind", "led_segment", "strip", "T", "first", 0, "last", 15);
            event(0, "Seg", "fill", "color", List.of(1, 2, 3), "first", 0, "last", 16);
            event(0, "T", "fill", "color", List.of(1, 2, 3), "first", 44);
            event(0, "Seg", "fill", "color", List.of(1, 2, 3), "first", 0, "last", 15);
            List<ShowIssue> issues = validate();
            assertThat(codes(issues)).containsExactly("E_SEGMENT_RANGE", "E_SEGMENT_RANGE");
            assertThat(issues.get(0).message()).contains("«last»=16 surt de «Seg» (0–15)");
            assertThat(issues.get(1).message()).contains("«first»=44 surt de «T» (0–43)");
        }
    }

    @Nested
    @DisplayName("timeline")
    class Timeline {

        @Test
        @DisplayName("every event needs an existing target and must start inside the show")
        void targetAndTime() {
            port("Focos", "kind", "relay", "pin", 27);
            event(0, "Nope", "turn_on");
            event(122001, "Focos", "turn_on");
            event(122000, "Focos", "turn_off");
            assertThat(errors()).containsExactly("E_TARGET_MISSING", "E_T_AFTER_END");
        }

        @Test
        @DisplayName("the action must belong to the kind of the target")
        void actionUnknown() {
            port("Focos", "kind", "relay", "pin", 27);
            event(0, "Focos", "fill");
            assertThat(errors()).containsExactly("E_ACTION_UNKNOWN");
        }

        @Test
        @DisplayName("parameters must be the catalog's, typed, with assets declared and required ones present")
        void params() {
            port("Focos", "kind", "relay", "pin", 27);
            port("Audio", "kind", "audio");
            port("Seg", "kind", "led_segment", "strip", "T", "first", 0, "last", 15);
            port("T", "kind", "led_strip", "pin", 13, "dma", 10, "channel", 1, "length", 44);
            event(0, "Focos", "turn_on", "volume", 3);
            event(0, "Focos", "turn_on", "time", "deu");
            event(0, "Audio", "play", "audio_name", "night.mp3");
            event(0, "Audio", "play");
            event(0, "Seg", "animate", "animation_type", "fade", "color", List.of(0, 0, 0), "density", 2);
            assets = new ShowAssets(List.of("nature.mp3"), List.of());
            assertThat(errors()).containsExactly("E_PARAM_UNKNOWN", "E_PARAM_TYPE", "E_ASSET_UNLISTED", "E_PARAM_REQUIRED",
                    "E_ANIMATION_PARAM");
            assertThat(warnings()).containsExactly("W_FADE_FROM_BLACK", "W_ASSET_UNUSED");
        }

        @Test
        @DisplayName("an animate without animation_type requires it and accepts any animation's parameters")
        void animateWithoutAnimation() {
            port("T", "kind", "led_strip", "pin", 13, "dma", 10, "channel", 1, "length", 44);
            event(0, "T", "animate", "density", 0.5);
            assertThat(errors()).containsExactly("E_PARAM_REQUIRED");
            event(0, "T", "animate", "density", 2.0);
            // Out of the catalog bounds: the device-side check reports it as a type problem.
            assertThat(errors()).containsExactly("E_PARAM_REQUIRED", "E_PARAM_TYPE", "E_PARAM_REQUIRED");
        }

        @Test
        @DisplayName("continuous actions on the same target warn when they overlap or have no duration")
        void overlap() {
            port("T", "kind", "led_strip", "pin", 13, "dma", 10, "channel", 1, "length", 44);
            event(0, "T", "animate", "animation_type", "fire", "duration", 5);
            event(4000, "T", "animate", "animation_type", "fire", "duration", 1);
            event(5000, "T", "animate", "animation_type", "fire");
            event(6000, "T", "animate", "animation_type", "fire", "duration", 1);
            List<ShowIssue> overlaps = validate().stream().filter(i -> i.code().equals("W_OVERLAP_CONTINUOUS")).toList();
            assertThat(overlaps).hasSize(2);
            assertThat(overlaps.get(0).message()).contains("0 ms i 4000 ms");
            assertThat(overlaps.get(1).message()).contains("5000 ms i 6000 ms");
            assertThat(overlaps.get(0).port()).isEqualTo("T");
        }

        @Test
        @DisplayName("piece ports without events warn; infrastructure and action-less kinds do not")
        void unusedPorts() {
            port("Focos", "kind", "relay", "pin", 27);
            port("T", "kind", "led_strip", "pin", 13, "dma", 10, "channel", 1, "length", 44);
            port("Boto", "kind", "button", "pin", 20);
            assertThat(warnings()).containsExactly("W_PORT_UNUSED");
            assertThat(validate().get(0).port()).isEqualTo("Focos");
        }

        @Test
        @DisplayName("the legacy player accepts a single motor, audio and video port")
        void singletons() {
            port("A1", "kind", "audio");
            port("A2", "kind", "audio");
            event(0, "A1", "stop");
            event(0, "A2", "stop");
            List<ShowIssue> issues = validate();
            assertThat(codes(issues)).containsExactly("W_LEGACY_SINGLE");
            assertThat(issues.get(0).message()).contains("audio; n'hi ha 2: A1, A2");
        }

        @Test
        @DisplayName("declared assets nobody uses warn")
        void unusedAssets() {
            assets = new ShowAssets(List.of("a.mp3"), List.of("v.mp4"));
            assertThat(warnings()).containsExactly("W_ASSET_UNUSED", "W_ASSET_UNUSED");
        }
    }
}
