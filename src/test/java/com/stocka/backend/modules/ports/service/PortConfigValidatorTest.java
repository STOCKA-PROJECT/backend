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
        return new HashMap<>(Map.of("pinR", 3, "pinL", 4, "sensorPin", 7, "downTime", 15));
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
            assertThatThrownBy(() -> sut.normalize(OutputKind.RELAY, Map.of("activeLow", true)))
                    .isInstanceOfSatisfying(ApiException.class, ex -> {
                        assertThat(ex.getCode()).isEqualTo(ErrorCodes.PORTS_CONFIG_UNKNOWN_KEY);
                        assertThat(ex.getParams()).containsEntry("key", "activeLow");
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
        @DisplayName("accepts three distinct pins and a down time")
        void should_acceptMotor() {
            Map<String, Object> out = sut.normalize(OutputKind.MOTOR, motor());
            assertThat(out.keySet()).containsExactly("pinR", "pinL", "sensorPin", "downTime");
            assertThat(out).containsEntry("downTime", 15);
        }

        @Test
        @DisplayName("accepts a decimal down time")
        void should_acceptDecimalDownTime() {
            Map<String, Object> cfg = motor();
            cfg.put("downTime", 12.5);
            assertThat(sut.normalize(OutputKind.MOTOR, cfg)).containsEntry("downTime", 12.5);
        }

        @Test
        @DisplayName("rejects repeated pins with ports.motor_pins_not_distinct")
        void should_rejectRepeatedPins() {
            Map<String, Object> cfg = motor();
            cfg.put("pinL", 3);
            assertCode(() -> sut.normalize(OutputKind.MOTOR, cfg), ErrorCodes.PORTS_MOTOR_PINS_NOT_DISTINCT);
        }

        @Test
        @DisplayName("rejects a negative down time and a missing pin")
        void should_rejectNegativeAndMissing() {
            Map<String, Object> negative = motor();
            negative.put("downTime", -1);
            assertCode(() -> sut.normalize(OutputKind.MOTOR, negative), ErrorCodes.PORTS_CONFIG_OUT_OF_RANGE);
            Map<String, Object> missing = motor();
            missing.remove("sensorPin");
            assertCode(() -> sut.normalize(OutputKind.MOTOR, missing), ErrorCodes.PORTS_CONFIG_MISSING_KEY);
        }

        @Test
        @DisplayName("exposes its three pins as config pins")
        void should_exposeConfigPins() {
            assertThat(sut.configPins(OutputKind.MOTOR, sut.normalize(OutputKind.MOTOR, motor()))).containsExactly(3, 4, 7);
            assertThat(sut.configPins(OutputKind.MOTOR, Map.of())).isEmpty();
        }
    }

    @Nested
    @DisplayName("kinds without config")
    class NoConfigKinds {

        @Test
        @DisplayName("audio, video and button accept only an empty config")
        void should_acceptOnlyEmpty() {
            for (OutputKind kind : List.of(OutputKind.AUDIO, OutputKind.VIDEO, OutputKind.BUTTON)) {
                assertThat(sut.normalize(kind, Map.of())).as(kind.name()).isEmpty();
                assertCode(() -> sut.normalize(kind, Map.of("file", "x.mp3")), ErrorCodes.PORTS_CONFIG_UNKNOWN_KEY);
            }
        }
    }
}
