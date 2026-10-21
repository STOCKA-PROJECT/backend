package com.stocka.backend.modules.devices.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.stocka.backend.modules.devices.dto.DeviceAlertDto;

@DisplayName("DeviceOverridesJsonCodec")
class DeviceOverridesJsonCodecTest {

    private final DeviceOverridesJsonCodec sut = new DeviceOverridesJsonCodec();

    @Nested
    @DisplayName("serialize")
    class Serialize {

        @Test
        @DisplayName("should write a JSON array preserving order")
        void serialize_should_writeArray() {
            assertThat(sut.serialize(List.of("Angel", "Riu"))).isEqualTo("[\"Angel\",\"Riu\"]");
        }

        @Test
        @DisplayName("should write [] for null or empty")
        void serialize_should_writeEmptyArray() {
            assertThat(sut.serialize(null)).isEqualTo("[]");
            assertThat(sut.serialize(List.of())).isEqualTo("[]");
        }

        @Test
        @DisplayName("should escape names with quotes and accents")
        void serialize_should_escape() {
            String json = sut.serialize(List.of("Casa \"gran\"", "Àngel"));
            assertThat(sut.deserialize(json)).containsExactly("Casa \"gran\"", "Àngel");
        }
    }

    @Nested
    @DisplayName("deserialize")
    class Deserialize {

        @Test
        @DisplayName("should read the array back")
        void deserialize_should_readArray() {
            assertThat(sut.deserialize("[\"Angel\",\"Riu\"]")).containsExactly("Angel", "Riu");
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        @DisplayName("should read null or blank as empty")
        void deserialize_should_readBlankAsEmpty(String json) {
            assertThat(sut.deserialize(json)).isEmpty();
        }

        @Test
        @DisplayName("should read corrupt JSON as empty instead of failing")
        void deserialize_should_toleratetCorruptJson() {
            assertThat(sut.deserialize("[\"Angel\"")).isEmpty();
            assertThat(sut.deserialize("{\"a\":1}")).isEmpty();
        }
    }

    @Nested
    @DisplayName("alerts")
    class Alerts {

        @Test
        @DisplayName("should round-trip the alert list")
        void alerts_should_roundTrip() {
            List<DeviceAlertDto> alerts = List.of(new DeviceAlertDto("motor_timeout", "Àngel", "No ha arribat \"a dalt\"", "2026-12-24T18:00:00Z"),
                    new DeviceAlertDto("sensor_panic", "Seta", null, null));
            assertThat(sut.deserializeAlerts(sut.serializeAlerts(alerts))).isEqualTo(alerts);
        }

        @Test
        @DisplayName("should write null or empty as an empty array")
        void serializeAlerts_should_writeEmptyArray() {
            assertThat(sut.serializeAlerts(null)).isEqualTo("[]");
            assertThat(sut.serializeAlerts(List.of())).isEqualTo("[]");
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "[{\"code\":", "{\"code\":\"x\"}"})
        @DisplayName("should read null, blank or corrupt JSON as empty")
        void deserializeAlerts_should_tolerateBadInput(String json) {
            assertThat(sut.deserializeAlerts(json)).isEmpty();
        }
    }
}
