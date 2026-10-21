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
    private ShowAssets assets = new ShowAssets(List.of());
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
            ShowAssets declared = new ShowAssets(List.of("nature.mp3"));
            assertThat(ShowValidator.validate(hw, events, "rpi4b", 122000, declared, catalog)).isEmpty();
        }
    }

    @Nested
    @DisplayName("relays and expanders (contract v3)")
    class RelaysAndExpanders {

        private void expander(String name, int address) {
            port(name, "kind", "gpio_expander", "chip", "mcp23017", "bus", 1, "address", address, "activeLow", true);
        }

        @Test
        @DisplayName("a relay takes a pin or expander + channel, never both")
        void pinXorExpander() {
            expander("exp1", 32);
            port("A", "kind", "relay", "pin", 26, "expander", "exp1", "channel", 1);
            port("B", "kind", "relay", "expander", "exp1", "channel", 2);
            port("C", "kind", "relay");
            port("D", "kind", "relay", "pin", 24, "channel", 3);
            port("E", "kind", "relay", "expander", "exp1");
            port("F", "kind", "relay", "expander", "exp1", "channel", 4, "activeLow", true);
            event(0, "A", "turn_on");
            event(0, "B", "turn_on");
            event(0, "C", "turn_on");
            event(0, "D", "turn_on");
            event(0, "E", "turn_on");
            event(0, "F", "turn_on");
            List<ShowIssue> issues = validate();
            assertThat(errors()).containsOnly("E_CONFIG_INVALID");
            assertThat(issues.stream().filter(i -> "E_CONFIG_INVALID".equals(i.code())).map(ShowIssue::port).toList())
                    .containsExactly("A", "C", "D", "E", "F");
        }

        @Test
        @DisplayName("a native relay may be active-low and still counts its pin")
        void activeLowNative() {
            port("A", "kind", "relay", "pin", 26, "activeLow", true);
            port("B", "kind", "relay", "pin", 26);
            event(0, "A", "pulse", "duration", 0.5);
            event(0, "B", "turn_on");
            assertThat(errors()).containsExactly("E_PIN_DUPLICATE");
        }

        @Test
        @DisplayName("the expander must exist as a gpio_expander and each channel takes one relay")
        void expanderRefAndChannels() {
            expander("exp1", 32);
            port("Strip", "kind", "led_strip", "pin", 13, "dma", 10, "channel", 1, "length", 10);
            port("A", "kind", "relay", "expander", "exp9", "channel", 1);
            port("B", "kind", "relay", "expander", "Strip", "channel", 1);
            port("C", "kind", "relay", "expander", "exp1", "channel", 3);
            port("D", "kind", "relay", "expander", "exp1", "channel", 3);
            for (String n : List.of("A", "B", "C", "D")) event(0, n, "turn_on");
            assertThat(errors()).containsExactly("E_EXPANDER_MISSING", "E_EXPANDER_MISSING", "E_EXPANDER_CHANNEL_DUPLICATE");
        }

        @Test
        @DisplayName("I2C addresses are unique per bus and GPIO 2/3 become reserved")
        void addressesAndI2cPins() {
            expander("exp1", 32);
            expander("exp2", 32);
            port("exp3", "kind", "gpio_expander", "chip", "mcp23017", "bus", 0, "address", 32);
            port("Sembra", "kind", "relay", "pin", 2);
            event(0, "Sembra", "turn_on");
            assertThat(errors()).containsExactly("E_PIN_RESERVED", "E_I2C_ADDRESS_DUPLICATE");
            assertThat(validate().stream().filter(i -> "E_PIN_RESERVED".equals(i.code())).findFirst().orElseThrow().message()).contains("I2C");
        }

        @Test
        @DisplayName("without an expander GPIO 2/3 are only a pull-up warning; expanders are not unused ports")
        void withoutExpander() {
            port("Sembra", "kind", "relay", "pin", 3);
            event(0, "Sembra", "turn_on");
            assertThat(errors()).isEmpty();
            assertThat(warnings()).containsExactly("W_PIN_PULLUP");
            expander("exp1", 32);
            port("Pou", "kind", "relay", "expander", "exp1", "channel", 0);
            event(1, "Pou", "pulse", "duration", 1);
            assertThat(warnings()).doesNotContain("W_PORT_UNUSED");
        }
    }

    @Nested
    @DisplayName("moving heads and motor (contract v3)")
    class HeadsAndMotor {

        @Test
        @DisplayName("a head must fit the universe and not overlap another")
        void dmxRanges() {
            port("F1", "kind", "moving_head", "profile", "varytec-hero-wash-712z", "address", 1);
            port("F2", "kind", "moving_head", "profile", "varytec-hero-wash-712z", "address", 15);
            port("F3", "kind", "moving_head", "profile", "varytec-hero-wash-712z", "address", 500);
            port("F4", "kind", "moving_head", "profile", "varytec-hero-wash-712z", "address", 498);
            for (String n : List.of("F1", "F2", "F3", "F4")) event(0, n, "set", "intensity", 50);
            assertThat(errors()).containsExactly("E_DMX_ADDRESS_OVERLAP", "E_DMX_ADDRESS_RANGE");
        }

        @Test
        @DisplayName("set takes physical units within the catalog ranges; an empty cue warns")
        void cues() {
            port("F", "kind", "moving_head", "profile", "varytec-hero-wash-712z", "address", 1);
            event(0, "F", "set", "pan", 270, "tilt", 95, "zoom", 15, "color", List.of(255L, 160L, 60L), "white", 40L, "intensity", 80, "time", 2);
            event(1000, "F", "blackout", "time", 1);
            assertThat(errors()).isEmpty();
            event(2000, "F", "set", "time", 2);
            assertThat(warnings()).containsExactly("W_EMPTY_CUE");
            event(3000, "F", "set", "pan", 541);
            event(4000, "F", "set", "zoom", 5);
            event(5000, "F", "set", "strobe", 1);
            assertThat(errors()).containsExactly("E_PARAM_TYPE", "E_PARAM_TYPE", "E_PARAM_UNKNOWN");
        }

        @Test
        @DisplayName("motors need both max times; a clip longer than one is an error, or a warning with a limit sensor")
        void motor() {
            port("Angel", "kind", "motor", "pinForward", 27, "pinReverse", 17, "maxTimeReverse", 18);
            event(0, "Angel", "reverse");
            assertThat(errors()).containsExactly("E_CONFIG_INVALID");
            hardware.get("Angel").put("maxTimeForward", 24);
            assertThat(errors()).isEmpty();
            event(1000, "Angel", "forward", "time", 25);
            assertThat(errors()).containsExactly("E_MOTOR_TIME");
            port("AngelDalt", "kind", "sensor", "pin", 22);
            hardware.get("Angel").put("limitForward", "AngelDalt");
            assertThat(errors()).isEmpty();
            assertThat(warnings()).containsExactly("W_MOTOR_TIME_CAPPED");
            event(2000, "Angel", "reverse", "time", 19);
            assertThat(errors()).containsExactly("E_MOTOR_TIME");
        }

        @Test
        @DisplayName("a clip within the max time, or without time, is clean; the old actions are gone")
        void motorActions() {
            port("Angel", "kind", "motor", "pinForward", 27, "pinReverse", 17, "maxTimeForward", 24, "maxTimeReverse", 18);
            event(0, "Angel", "forward", "time", 24);
            event(30000, "Angel", "reverse");
            event(60000, "Angel", "stop");
            assertThat(validate()).isEmpty();
            event(90000, "Angel", "start_up");
            assertThat(errors()).containsExactly("E_ACTION_UNKNOWN");
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
            port("M", "kind", "motor", "pinForward", 26, "pinReverse", 27, "maxTimeForward", 20, "maxTimeReverse", 15);
            event(0, "A", "turn_on");
            event(0, "M", "stop");
            List<ShowIssue> issues = validate();
            assertThat(codes(issues)).containsExactly("E_PIN_DUPLICATE");
            assertThat(issues.get(0).message()).contains("A, M.pinForward");
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
    @DisplayName("motor outputs and sensors (contract v3)")
    class MotorsAndSensors {

        private void expander(String name) {
            port(name, "kind", "gpio_expander", "chip", "mcp23017", "address", 32);
        }

        private void nativeMotor(String name, Object... extra) {
            port(name, "kind", "motor", "pinForward", 27, "pinReverse", 17, "maxTimeForward", 24, "maxTimeReverse", 18);
            hardware.get(name).putAll(Belen2026Fixture.config(extra));
            event(0, name, "stop");
        }

        private void expanderMotor(String name, Object... extra) {
            port(name, "kind", "motor", "expander", "exp1", "channelForward", 6, "channelReverse", 7,
                    "maxTimeForward", 60, "maxTimeReverse", 60);
            hardware.get(name).putAll(Belen2026Fixture.config(extra));
            event(0, name, "stop");
        }

        @Test
        @DisplayName("a motor on the expander with two distinct channels is clean")
        void expanderMotorClean() {
            expander("exp1");
            expanderMotor("Noria");
            assertThat(validate()).isEmpty();
        }

        @Test
        @DisplayName("native outputs: a missing pin or stray channels are E_MOTOR_OUTPUTS")
        void nativeOutputsIncoherent() {
            nativeMotor("A");
            hardware.get("A").remove("pinReverse");
            assertThat(errors()).contains("E_MOTOR_OUTPUTS");
            hardware.get("A").put("pinReverse", 17);
            hardware.get("A").put("channelForward", 1);
            assertThat(errors()).containsExactly("E_MOTOR_OUTPUTS");
            assertThat(validate().get(0).message()).contains("només tenen sentit amb «expander»");
        }

        @Test
        @DisplayName("expander outputs: pins, a missing or repeated channel and activeLow are E_MOTOR_OUTPUTS")
        void expanderOutputsIncoherent() {
            expander("exp1");
            expanderMotor("N1", "pinForward", 5);
            port("N2", "kind", "motor", "expander", "exp1", "channelForward", 8, "maxTimeForward", 1, "maxTimeReverse", 1);
            event(0, "N2", "stop");
            port("N3", "kind", "motor", "expander", "exp1", "channelForward", 9, "channelReverse", 9,
                    "maxTimeForward", 1, "maxTimeReverse", 1);
            event(0, "N3", "stop");
            port("N4", "kind", "motor", "expander", "exp1", "channelForward", 10, "channelReverse", 11, "activeLow", true,
                    "maxTimeForward", 1, "maxTimeReverse", 1);
            event(0, "N4", "stop");
            List<ShowIssue> outputs = validate().stream().filter(i -> "E_MOTOR_OUTPUTS".equals(i.code())).toList();
            assertThat(outputs).extracting(ShowIssue::port).containsExactly("N1", "N2", "N3", "N4");
        }

        @Test
        @DisplayName("a motor repeating its own native pin is E_PIN_DUPLICATE")
        void motorPinsMustDiffer() {
            nativeMotor("M", "pinReverse", 27);
            List<ShowIssue> issues = validate();
            assertThat(codes(issues)).containsExactly("E_PIN_DUPLICATE");
            assertThat(issues.get(0).message()).contains("M.pinForward, M.pinReverse");
        }

        @Test
        @DisplayName("motor channels count against relays and sensors on the same expander")
        void sharedChannels() {
            expander("exp1");
            expanderMotor("Noria");
            port("R", "kind", "relay", "expander", "exp1", "channel", 7);
            event(0, "R", "turn_on");
            List<ShowIssue> issues = validate();
            assertThat(codes(issues)).containsExactly("E_EXPANDER_CHANNEL_DUPLICATE");
            assertThat(issues.get(0).message()).contains("Noria.channelReverse, R");
            hardware.remove("R");
            timeline.removeIf(e -> "R".equals(e.target()));
            port("S", "kind", "sensor", "expander", "exp1", "channel", 6, "onActive", "play");
            assertThat(errors()).containsExactly("E_EXPANDER_CHANNEL_DUPLICATE");
        }

        @Test
        @DisplayName("a motor or sensor on a missing expander is E_EXPANDER_MISSING")
        void missingExpander() {
            expanderMotor("Noria");
            port("S", "kind", "sensor", "expander", "exp9", "channel", 1, "onActive", "play");
            assertThat(errors()).containsExactly("E_EXPANDER_MISSING", "E_EXPANDER_MISSING");
        }

        @Test
        @DisplayName("a limit must name a sensor port")
        void limitMustBeSensor() {
            port("Focos", "kind", "relay", "pin", 26);
            event(0, "Focos", "turn_on");
            nativeMotor("Angel", "limitForward", "Fantasma", "limitReverse", "Focos");
            assertThat(errors()).containsExactly("E_SENSOR_MISSING", "E_SENSOR_MISSING");
            port("AngelDalt", "kind", "sensor", "pin", 22);
            hardware.get("Angel").put("limitForward", "AngelDalt");
            hardware.get("Angel").remove("limitReverse");
            assertThat(validate()).isEmpty();
        }

        @Test
        @DisplayName("motor pins are reserved on I2C GPIO 2/3 when an expander exists")
        void motorPinsOnI2c() {
            expander("exp1");
            nativeMotor("Angel", "pinForward", 3);
            assertThat(errors()).containsExactly("E_PIN_RESERVED");
        }

        @Test
        @DisplayName("sensor pins count as pins")
        void sensorPinsCount() {
            nativeMotor("Angel");
            port("S", "kind", "sensor", "pin", 17, "onActive", "play");
            assertThat(errors()).containsExactly("E_PIN_DUPLICATE");
        }

        @Test
        @DisplayName("a sensor on an expander may set its polarity and pull none, never pull down")
        void expanderSensorPull() {
            expander("exp1");
            port("S", "kind", "sensor", "expander", "exp1", "channel", 1, "activeLow", false, "pull", "none", "onActive", "play");
            assertThat(validate()).isEmpty();
            hardware.get("S").put("pull", "down");
            assertThat(errors()).containsExactly("E_CONFIG_INVALID");
        }

        @Test
        @DisplayName("a sensor takes a pin or expander + channel")
        void sensorPinXorExpander() {
            expander("exp1");
            port("S", "kind", "sensor", "pin", 22, "expander", "exp1", "channel", 1, "onActive", "play");
            assertThat(errors()).contains("E_CONFIG_INVALID");
            port("S", "kind", "sensor", "onActive", "play");
            assertThat(errors()).contains("E_CONFIG_INVALID");
        }

        @Test
        @DisplayName("an unknown onActive is E_CONFIG_INVALID")
        void unknownOnActive() {
            port("S", "kind", "sensor", "pin", 22, "onActive", "explode");
            assertThat(errors()).containsExactly("E_CONFIG_INVALID");
        }

        @Test
        @DisplayName("a sensor without onActive that no motor uses warns W_SENSOR_UNUSED; a limit sensor does not")
        void unusedSensor() {
            port("Solt", "kind", "sensor", "pin", 23);
            port("AngelDalt", "kind", "sensor", "pin", 22, "onActive", "none");
            nativeMotor("Angel", "limitForward", "AngelDalt");
            List<ShowIssue> issues = validate();
            assertThat(codes(issues)).containsExactly("W_SENSOR_UNUSED");
            assertThat(issues.get(0).port()).isEqualTo("Solt");
        }

        @Test
        @DisplayName("sensors have no actions")
        void sensorsHaveNoActions() {
            port("S", "kind", "sensor", "pin", 22, "onActive", "play");
            event(0, "S", "press");
            assertThat(errors()).containsExactly("E_ACTION_UNKNOWN");
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
            assets = new ShowAssets(List.of("nature.mp3"));
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
            port("Boto", "kind", "sensor", "pin", 20, "onActive", "play");
            assertThat(warnings()).containsExactly("W_PORT_UNUSED");
            assertThat(validate().get(0).port()).isEqualTo("Focos");
        }

        @Test
        @DisplayName("the player accepts a single audio port; several motors are fine")
        void singletons() {
            port("M1", "kind", "motor", "pinForward", 22, "pinReverse", 23, "maxTimeForward", 10, "maxTimeReverse", 10);
            port("M2", "kind", "motor", "pinForward", 12, "pinReverse", 16, "maxTimeForward", 10, "maxTimeReverse", 10);
            event(0, "M1", "stop");
            event(0, "M2", "stop");
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
            assets = new ShowAssets(List.of("a.mp3", "b.mp3"));
            assertThat(warnings()).containsExactly("W_ASSET_UNUSED", "W_ASSET_UNUSED");
        }
    }
}
