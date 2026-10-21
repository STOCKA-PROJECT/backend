package com.stocka.backend.modules.ports.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.stocka.backend.modules.common.error.ApiException;
import com.stocka.backend.modules.common.error.ErrorCodes;
import com.stocka.backend.modules.contract.OutputCatalog;
import com.stocka.backend.modules.contract.OutputKind;

@DisplayName("PortConfigValidator")
class PortConfigValidatorTest {

    private static PortConfigValidator sut;

    @BeforeAll
    static void setUp() {
        sut = new PortConfigValidator(new OutputCatalog());
    }

    private static Map<String, Object> strip() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("length", 300);
        m.put("channel", 1);
        m.put("dma", 10);
        return m;
    }

    private static Map<String, Object> segment() {
        return new HashMap<>(Map.of("strip", "LedStrip3", "first", 0, "last", 148));
    }

    private static Map<String, Object> motor() {
        return new HashMap<>(Map.of("pinForward", 3, "pinReverse", 4, "maxTimeForward", 20, "maxTimeReverse", 15));
    }

    private static Map<String, Object> expanderMotor() {
        return new HashMap<>(Map.of("expander", "exp1", "channelForward", 6, "channelReverse", 7, "maxTimeForward", 60, "maxTimeReverse", 60));
    }

    private static void assertMotorOutputs(Map<String, Object> cfg, String reason) {
        assertThatThrownBy(() -> sut.normalize(OutputKind.MOTOR, cfg))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(ErrorCodes.PORTS_MOTOR_OUTPUTS_INVALID);
                    assertThat(ex.getParams()).containsEntry("reason", reason);
                });
    }

    private static void assertCode(Runnable call, String code) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ex.getCode()).isEqualTo(code);
                });
    }

    @Nested
    @DisplayName("relay")
    class Relay {

        @Test
        @DisplayName("accepts null and empty config as empty")
        void should_acceptEmpty() {
            assertThat(sut.normalize(OutputKind.RELAY, null)).isEmpty();
            assertThat(sut.normalize(OutputKind.RELAY, Map.of())).isEmpty();
        }

        @Test
        @DisplayName("rejects an unknown key with ports.config_unknown_key")
        void should_rejectUnknownKey() {
            assertThatThrownBy(() -> sut.normalize(OutputKind.RELAY, Map.of("dma", 10)))
                    .isInstanceOfSatisfying(ApiException.class, ex -> {
                        assertThat(ex.getCode()).isEqualTo(ErrorCodes.PORTS_CONFIG_UNKNOWN_KEY);
                        assertThat(ex.getParams()).containsEntry("key", "dma");
                    });
        }

        @Test
        @DisplayName("ignores a pin sent inside the config because it lives in Port.pin")
        void should_ignorePinKey() {
            assertThat(sut.normalize(OutputKind.RELAY, Map.of("pin", 27))).isEmpty();
        }

        @Test
        @DisplayName("has no config pins")
        void should_haveNoConfigPins() {
            assertThat(sut.configPins(OutputKind.RELAY, Map.of())).isEmpty();
            assertThat(sut.configPins(OutputKind.RELAY, null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("led_strip")
    class LedStrip {

        @Test
        @DisplayName("returns the keys in catalog order without injecting defaults")
        void should_normalizeInCatalogOrder() {
            Map<String, Object> out = sut.normalize(OutputKind.LED_STRIP, strip());
            assertThat(out.keySet()).containsExactly("dma", "channel", "length");
            assertThat(out).containsEntry("dma", 10).containsEntry("channel", 1).containsEntry("length", 300);
            assertThat(out).doesNotContainKey("brightness");
        }

        @Test
        @DisplayName("keeps optional keys when present")
        void should_keepOptionals() {
            Map<String, Object> cfg = strip();
            cfg.put("brightness", 128);
            cfg.put("invert", true);
            cfg.put("freq_hz", 400000);
            Map<String, Object> out = sut.normalize(OutputKind.LED_STRIP, cfg);
            assertThat(out).containsEntry("brightness", 128).containsEntry("invert", true).containsEntry("freq_hz", 400000);
        }

        @Test
        @DisplayName("rejects a missing required key with ports.config_missing_key")
        void should_rejectMissingRequired() {
            Map<String, Object> cfg = strip();
            cfg.remove("length");
            assertThatThrownBy(() -> sut.normalize(OutputKind.LED_STRIP, cfg))
                    .isInstanceOfSatisfying(ApiException.class, ex -> {
                        assertThat(ex.getCode()).isEqualTo(ErrorCodes.PORTS_CONFIG_MISSING_KEY);
                        assertThat(ex.getParams()).containsEntry("key", "length");
                    });
        }

        @Test
        @DisplayName("rejects values outside the catalog bounds with ports.config_out_of_range")
        void should_rejectOutOfRange() {
            Map<String, Object> dma = strip();
            dma.put("dma", 15);
            assertCode(() -> sut.normalize(OutputKind.LED_STRIP, dma), ErrorCodes.PORTS_CONFIG_OUT_OF_RANGE);
            Map<String, Object> channel = strip();
            channel.put("channel", 2);
            assertCode(() -> sut.normalize(OutputKind.LED_STRIP, channel), ErrorCodes.PORTS_CONFIG_OUT_OF_RANGE);
            Map<String, Object> brightness = strip();
            brightness.put("brightness", 300);
            assertCode(() -> sut.normalize(OutputKind.LED_STRIP, brightness), ErrorCodes.PORTS_CONFIG_OUT_OF_RANGE);
            Map<String, Object> length = strip();
            length.put("length", 0);
            assertCode(() -> sut.normalize(OutputKind.LED_STRIP, length), ErrorCodes.PORTS_CONFIG_OUT_OF_RANGE);
            Map<String, Object> freq = strip();
            freq.put("freq_hz", 0);
            assertThatThrownBy(() -> sut.normalize(OutputKind.LED_STRIP, freq))
                    .isInstanceOfSatisfying(ApiException.class, ex -> {
                        assertThat(ex.getCode()).isEqualTo(ErrorCodes.PORTS_CONFIG_OUT_OF_RANGE);
                        assertThat(ex.getParams()).containsEntry("key", "freq_hz").containsEntry("min", "1").containsEntry("max", "-");
                    });
        }

        @Test
        @DisplayName("rejects wrong JSON types with ports.config_type_mismatch")
        void should_rejectTypeMismatch() {
            Map<String, Object> asText = strip();
            asText.put("dma", "10");
            assertThatThrownBy(() -> sut.normalize(OutputKind.LED_STRIP, asText))
                    .isInstanceOfSatisfying(ApiException.class, ex -> {
                        assertThat(ex.getCode()).isEqualTo(ErrorCodes.PORTS_CONFIG_TYPE_MISMATCH);
                        assertThat(ex.getParams()).containsEntry("key", "dma").containsEntry("type", "int");
                    });
            Map<String, Object> fractional = strip();
            fractional.put("dma", 1.5);
            assertCode(() -> sut.normalize(OutputKind.LED_STRIP, fractional), ErrorCodes.PORTS_CONFIG_TYPE_MISMATCH);
            Map<String, Object> bool = strip();
            bool.put("dma", true);
            assertCode(() -> sut.normalize(OutputKind.LED_STRIP, bool), ErrorCodes.PORTS_CONFIG_TYPE_MISMATCH);
            Map<String, Object> invert = strip();
            invert.put("invert", "yes");
            assertCode(() -> sut.normalize(OutputKind.LED_STRIP, invert), ErrorCodes.PORTS_CONFIG_TYPE_MISMATCH);
        }

        @Test
        @DisplayName("accepts an integral double and a long as int")
        void should_acceptIntegralNumbers() {
            Map<String, Object> cfg = strip();
            cfg.put("dma", 10.0);
            cfg.put("length", 300L);
            Map<String, Object> out = sut.normalize(OutputKind.LED_STRIP, cfg);
            assertThat(out).containsEntry("dma", 10).containsEntry("length", 300);
        }
    }

    @Nested
    @DisplayName("led_segment")
    class LedSegment {

        @Test
        @DisplayName("accepts strip, first and last")
        void should_acceptSegment() {
            Map<String, Object> out = sut.normalize(OutputKind.LED_SEGMENT, segment());
            assertThat(out.keySet()).containsExactly("strip", "first", "last");
            assertThat(out).containsEntry("strip", "LedStrip3");
        }

        @Test
        @DisplayName("trims the strip name")
        void should_trimStripName() {
            Map<String, Object> cfg = segment();
            cfg.put("strip", "  LedStrip3 ");
            assertThat(sut.normalize(OutputKind.LED_SEGMENT, cfg)).containsEntry("strip", "LedStrip3");
        }

        @Test
        @DisplayName("rejects a blank or non-text strip name")
        void should_rejectBadStrip() {
            Map<String, Object> blank = segment();
            blank.put("strip", "   ");
            assertCode(() -> sut.normalize(OutputKind.LED_SEGMENT, blank), ErrorCodes.PORTS_CONFIG_TYPE_MISMATCH);
            Map<String, Object> number = segment();
            number.put("strip", 5);
            assertCode(() -> sut.normalize(OutputKind.LED_SEGMENT, number), ErrorCodes.PORTS_CONFIG_TYPE_MISMATCH);
        }

        @Test
        @DisplayName("rejects negative bounds and missing keys")
        void should_rejectNegativeAndMissing() {
            Map<String, Object> negative = segment();
            negative.put("first", -1);
            assertCode(() -> sut.normalize(OutputKind.LED_SEGMENT, negative), ErrorCodes.PORTS_CONFIG_OUT_OF_RANGE);
            Map<String, Object> missing = segment();
            missing.remove("strip");
            assertCode(() -> sut.normalize(OutputKind.LED_SEGMENT, missing), ErrorCodes.PORTS_CONFIG_MISSING_KEY);
        }

        @Test
        @DisplayName("accepts a reversed range; the strip length check happens in the service")
        void should_acceptReversed() {
            Map<String, Object> cfg = segment();
            cfg.put("first", 298);
            cfg.put("last", 149);
            assertThat(sut.normalize(OutputKind.LED_SEGMENT, cfg)).containsEntry("first", 298).containsEntry("last", 149);
        }
    }

    @Nested
    @DisplayName("motor")
    class Motor {

        @Test
        @DisplayName("accepts two distinct native pins and both max times, in catalog order")
        void should_acceptNativeMotor() {
            Map<String, Object> out = sut.normalize(OutputKind.MOTOR, motor());
            assertThat(out.keySet()).containsExactly("pinForward", "pinReverse", "maxTimeForward", "maxTimeReverse");
            assertThat(out).containsEntry("maxTimeReverse", 15);
        }

        @Test
        @DisplayName("accepts decimal max times, limit sensors, lockOnTimeout, activeLow and switchDelayMs")
        void should_acceptOptionalKeys() {
            Map<String, Object> cfg = motor();
            cfg.put("maxTimeReverse", 12.5);
            cfg.put("limitForward", "AngelDalt");
            cfg.put("limitReverse", "AngelBaix");
            cfg.put("lockOnTimeout", true);
            cfg.put("activeLow", true);
            cfg.put("switchDelayMs", 100);
            assertThat(sut.normalize(OutputKind.MOTOR, cfg))
                    .containsEntry("maxTimeReverse", 12.5)
                    .containsEntry("limitForward", "AngelDalt")
                    .containsEntry("limitReverse", "AngelBaix")
                    .containsEntry("lockOnTimeout", true)
                    .containsEntry("activeLow", true)
                    .containsEntry("switchDelayMs", 100);
        }

        @Test
        @DisplayName("accepts the expander with two distinct channels")
        void should_acceptExpanderMotor() {
            assertThat(sut.normalize(OutputKind.MOTOR, expanderMotor()).keySet())
                    .containsExactly("expander", "channelForward", "channelReverse", "maxTimeForward", "maxTimeReverse");
        }

        @Test
        @DisplayName("rejects a repeated native pin with ports.motor_pins_not_distinct")
        void should_rejectRepeatedPins() {
            Map<String, Object> cfg = motor();
            cfg.put("pinReverse", 3);
            assertCode(() -> sut.normalize(OutputKind.MOTOR, cfg), ErrorCodes.PORTS_MOTOR_PINS_NOT_DISTINCT);
        }

        @Test
        @DisplayName("rejects a repeated expander channel with ports.motor_pins_not_distinct")
        void should_rejectRepeatedChannels() {
            Map<String, Object> cfg = expanderMotor();
            cfg.put("channelReverse", 6);
            assertCode(() -> sut.normalize(OutputKind.MOTOR, cfg), ErrorCodes.PORTS_MOTOR_PINS_NOT_DISTINCT);
        }

        @Test
        @DisplayName("rejects a missing native pin with reason missing_pin")
        void should_rejectMissingPin() {
            Map<String, Object> cfg = motor();
            cfg.remove("pinReverse");
            assertMotorOutputs(cfg, "missing_pin");
            Map<String, Object> none = motor();
            none.remove("pinForward");
            none.remove("pinReverse");
            assertMotorOutputs(none, "missing_pin");
        }

        @Test
        @DisplayName("rejects pins mixed with an expander with reason pins_and_expander")
        void should_rejectPinsAndExpander() {
            Map<String, Object> cfg = expanderMotor();
            cfg.put("pinForward", 3);
            assertMotorOutputs(cfg, "pins_and_expander");
        }

        @Test
        @DisplayName("rejects an expander with a single channel with reason missing_channel")
        void should_rejectMissingChannel() {
            Map<String, Object> cfg = expanderMotor();
            cfg.remove("channelReverse");
            assertMotorOutputs(cfg, "missing_channel");
        }

        @Test
        @DisplayName("rejects channels without an expander and activeLow on an expander")
        void should_rejectInconsistentExpanderKeys() {
            Map<String, Object> channels = motor();
            channels.put("channelForward", 1);
            assertCode(() -> sut.normalize(OutputKind.MOTOR, channels), ErrorCodes.PORTS_CHANNEL_WITHOUT_EXPANDER);
            Map<String, Object> activeLow = expanderMotor();
            activeLow.put("activeLow", true);
            assertCode(() -> sut.normalize(OutputKind.MOTOR, activeLow), ErrorCodes.PORTS_ACTIVE_LOW_ON_EXPANDER);
        }

        @Test
        @DisplayName("rejects negative max times, an out-of-range switch delay and the old keys")
        void should_rejectOutOfRangeAndLegacy() {
            Map<String, Object> negative = motor();
            negative.put("maxTimeForward", -1);
            assertCode(() -> sut.normalize(OutputKind.MOTOR, negative), ErrorCodes.PORTS_CONFIG_OUT_OF_RANGE);
            Map<String, Object> delay = motor();
            delay.put("switchDelayMs", 5001);
            assertCode(() -> sut.normalize(OutputKind.MOTOR, delay), ErrorCodes.PORTS_CONFIG_OUT_OF_RANGE);
            Map<String, Object> legacy = motor();
            legacy.put("sensorPin", 7);
            assertCode(() -> sut.normalize(OutputKind.MOTOR, legacy), ErrorCodes.PORTS_CONFIG_UNKNOWN_KEY);
        }

        @Test
        @DisplayName("requires both max times")
        void should_requireMaxTimes() {
            for (String key : List.of("maxTimeForward", "maxTimeReverse")) {
                Map<String, Object> cfg = motor();
                cfg.remove(key);
                assertCode(() -> sut.normalize(OutputKind.MOTOR, cfg), ErrorCodes.PORTS_CONFIG_MISSING_KEY);
            }
        }

        @Test
        @DisplayName("exposes its two native pins as config pins, none on an expander")
        void should_exposeConfigPins() {
            assertThat(sut.configPins(OutputKind.MOTOR, sut.normalize(OutputKind.MOTOR, motor()))).containsExactly(3, 4);
            assertThat(sut.configPins(OutputKind.MOTOR, sut.normalize(OutputKind.MOTOR, expanderMotor()))).isEmpty();
            assertThat(sut.configPins(OutputKind.MOTOR, Map.of())).isEmpty();
            assertThat(sut.configPins(OutputKind.RELAY, Map.of("pinForward", 3))).isEmpty();
        }

        @Test
        @DisplayName("exposes its two expander channels, none when native")
        void should_exposeExpanderChannels() {
            assertThat(sut.expanderChannels(OutputKind.MOTOR, expanderMotor())).containsExactly(6, 7);
            assertThat(sut.expanderChannels(OutputKind.MOTOR, motor())).isEmpty();
            assertThat(sut.expanderChannels(OutputKind.MOTOR, null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("sensor")
    class Sensor {

        @Test
        @DisplayName("a native sensor accepts an empty config and every optional key")
        void should_acceptNativeSensor() {
            assertThat(sut.normalize(OutputKind.SENSOR, Map.of())).isEmpty();
            assertThat(sut.normalize(OutputKind.SENSOR, Map.of("activeLow", false, "pull", "down", "debounceMs", 20, "onActive", "play")))
                    .containsExactly(Map.entry("activeLow", false), Map.entry("pull", "down"), Map.entry("debounceMs", 20),
                            Map.entry("onActive", "play"));
        }

        @Test
        @DisplayName("a sensor on an expander takes a channel, its own polarity and pull up or none")
        void should_acceptExpanderSensor() {
            assertThat(sut.normalize(OutputKind.SENSOR, Map.of("expander", "exp1", "channel", 15, "activeLow", true, "pull", "none")))
                    .containsEntry("channel", 15).containsEntry("activeLow", true).containsEntry("pull", "none");
            assertThat(sut.expanderChannels(OutputKind.SENSOR, Map.of("expander", "exp1", "channel", 15))).containsExactly(15);
        }

        @Test
        @DisplayName("rejects pull down on an expander")
        void should_rejectPullDownOnExpander() {
            assertCode(() -> sut.normalize(OutputKind.SENSOR, Map.of("expander", "exp1", "channel", 1, "pull", "down")),
                    ErrorCodes.PORTS_SENSOR_PULL_ON_EXPANDER);
        }

        @Test
        @DisplayName("rejects a channel without expander and an expander without channel")
        void should_rejectInconsistentChannel() {
            assertCode(() -> sut.normalize(OutputKind.SENSOR, Map.of("channel", 3)), ErrorCodes.PORTS_CHANNEL_WITHOUT_EXPANDER);
            assertCode(() -> sut.normalize(OutputKind.SENSOR, Map.of("expander", "exp1")), ErrorCodes.PORTS_CHANNEL_REQUIRED);
        }

        @Test
        @DisplayName("rejects unknown enum values and an out-of-range debounce")
        void should_rejectBadValues() {
            assertCode(() -> sut.normalize(OutputKind.SENSOR, Map.of("onActive", "explode")), ErrorCodes.PORTS_CONFIG_TYPE_MISMATCH);
            assertCode(() -> sut.normalize(OutputKind.SENSOR, Map.of("pull", "sideways")), ErrorCodes.PORTS_CONFIG_TYPE_MISMATCH);
            assertCode(() -> sut.normalize(OutputKind.SENSOR, Map.of("debounceMs", 2001)), ErrorCodes.PORTS_CONFIG_OUT_OF_RANGE);
        }
    }

    @Nested
    @DisplayName("relay on an expander (contract v3)")
    class RelayExpander {

        @Test
        @DisplayName("accepts expander + channel and a native activeLow flag")
        void should_acceptExpanderChannel() {
            assertThat(sut.normalize(OutputKind.RELAY, Map.of("expander", "exp1", "channel", 3)))
                    .containsExactly(Map.entry("expander", "exp1"), Map.entry("channel", 3));
            assertThat(sut.normalize(OutputKind.RELAY, Map.of("activeLow", true))).containsExactly(Map.entry("activeLow", true));
        }

        @Test
        @DisplayName("rejects channel without expander, expander without channel and activeLow on an expander")
        void should_rejectInconsistentRelay() {
            assertCode(() -> sut.normalize(OutputKind.RELAY, Map.of("channel", 3)), ErrorCodes.PORTS_CHANNEL_WITHOUT_EXPANDER);
            assertCode(() -> sut.normalize(OutputKind.RELAY, Map.of("expander", "exp1")), ErrorCodes.PORTS_CHANNEL_REQUIRED);
            assertCode(() -> sut.normalize(OutputKind.RELAY, Map.of("expander", "exp1", "channel", 1, "activeLow", true)),
                    ErrorCodes.PORTS_ACTIVE_LOW_ON_EXPANDER);
        }

        @Test
        @DisplayName("bounds the channel to 0–15 and types the keys")
        void should_boundChannel() {
            assertCode(() -> sut.normalize(OutputKind.RELAY, Map.of("expander", "exp1", "channel", 16)), ErrorCodes.PORTS_CONFIG_OUT_OF_RANGE);
            assertCode(() -> sut.normalize(OutputKind.RELAY, Map.of("expander", "exp1", "channel", -1)), ErrorCodes.PORTS_CONFIG_OUT_OF_RANGE);
            assertCode(() -> sut.normalize(OutputKind.RELAY, Map.of("expander", "exp1", "channel", "3")), ErrorCodes.PORTS_CONFIG_TYPE_MISMATCH);
            assertCode(() -> sut.normalize(OutputKind.RELAY, Map.of("expander", " ", "channel", 3)), ErrorCodes.PORTS_CONFIG_TYPE_MISMATCH);
            assertCode(() -> sut.normalize(OutputKind.RELAY, Map.of("activeLow", "yes")), ErrorCodes.PORTS_CONFIG_TYPE_MISMATCH);
        }
    }

    @Nested
    @DisplayName("gpio_expander and moving_head (contract v3)")
    class NewKinds {

        @Test
        @DisplayName("an expander needs chip and address in 0x20–0x27")
        void should_validateExpander() {
            assertThat(sut.normalize(OutputKind.GPIO_EXPANDER, Map.of("chip", "mcp23017", "address", 32, "activeLow", true)))
                    .containsExactly(Map.entry("chip", "mcp23017"), Map.entry("address", 32), Map.entry("activeLow", true));
            assertCode(() -> sut.normalize(OutputKind.GPIO_EXPANDER, Map.of("address", 32)), ErrorCodes.PORTS_CONFIG_MISSING_KEY);
            assertCode(() -> sut.normalize(OutputKind.GPIO_EXPANDER, Map.of("chip", "mcp23017")), ErrorCodes.PORTS_CONFIG_MISSING_KEY);
            assertCode(() -> sut.normalize(OutputKind.GPIO_EXPANDER, Map.of("chip", "pcf8574", "address", 32)), ErrorCodes.PORTS_CONFIG_TYPE_MISMATCH);
            assertCode(() -> sut.normalize(OutputKind.GPIO_EXPANDER, Map.of("chip", "mcp23017", "address", 40)), ErrorCodes.PORTS_CONFIG_OUT_OF_RANGE);
            assertCode(() -> sut.normalize(OutputKind.GPIO_EXPANDER, Map.of("chip", "mcp23017", "address", 32, "bus", 2)), ErrorCodes.PORTS_CONFIG_OUT_OF_RANGE);
        }

        @Test
        @DisplayName("a moving head needs a known profile and an address in 1–512")
        void should_validateMovingHead() {
            assertThat(sut.normalize(OutputKind.MOVING_HEAD, Map.of("profile", "varytec-hero-wash-712z", "address", 1)))
                    .containsExactly(Map.entry("profile", "varytec-hero-wash-712z"), Map.entry("address", 1));
            assertCode(() -> sut.normalize(OutputKind.MOVING_HEAD, Map.of("address", 1)), ErrorCodes.PORTS_CONFIG_MISSING_KEY);
            assertCode(() -> sut.normalize(OutputKind.MOVING_HEAD, Map.of("profile", "generic", "address", 1)), ErrorCodes.PORTS_CONFIG_TYPE_MISMATCH);
            assertCode(() -> sut.normalize(OutputKind.MOVING_HEAD, Map.of("profile", "varytec-hero-wash-712z", "address", 0)), ErrorCodes.PORTS_CONFIG_OUT_OF_RANGE);
            assertCode(() -> sut.normalize(OutputKind.MOVING_HEAD, Map.of("profile", "varytec-hero-wash-712z", "address", 513)), ErrorCodes.PORTS_CONFIG_OUT_OF_RANGE);
        }
    }

    @Nested
    @DisplayName("kinds without config")
    class NoConfigKinds {

        @Test
        @DisplayName("audio accepts only an empty config")
        void should_acceptOnlyEmpty() {
            for (OutputKind kind : List.of(OutputKind.AUDIO)) {
                assertThat(sut.normalize(kind, Map.of())).as(kind.name()).isEmpty();
                assertCode(() -> sut.normalize(kind, Map.of("file", "x.mp3")), ErrorCodes.PORTS_CONFIG_UNKNOWN_KEY);
            }
        }
    }
}
