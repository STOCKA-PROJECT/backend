package com.stocka.backend.modules.contract;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import tools.jackson.databind.json.JsonMapper;

@DisplayName("OutputKind")
class OutputKindTest {

    @Nested
    @DisplayName("parse")
    class Parse {

        @ParameterizedTest
        @EnumSource(OutputKind.class)
        @DisplayName("round-trips every constant through its catalog id")
        void should_roundTripCatalogId(OutputKind kind) {
            assertThat(OutputKind.parse(kind.catalogId())).contains(kind);
            assertThat(OutputKind.fromCatalogId(kind.catalogId())).isSameAs(kind);
        }

        @Test
        @DisplayName("uses snake_case ids that match the catalog keys")
        void should_useSnakeCaseIds() {
            assertThat(OutputKind.LED_STRIP.catalogId()).isEqualTo("led_strip");
            assertThat(OutputKind.LED_SEGMENT.catalogId()).isEqualTo("led_segment");
            assertThat(OutputKind.RELAY.catalogId()).isEqualTo("relay");
        }

        @Test
        @DisplayName("trims surrounding whitespace")
        void should_trim() {
            assertThat(OutputKind.parse("  motor ")).contains(OutputKind.MOTOR);
        }

        @Test
        @DisplayName("returns empty for unknown, upper-case, blank and null ids")
        void should_rejectUnknown() {
            assertThat(OutputKind.parse("laser")).isEmpty();
            assertThat(OutputKind.parse("RELAY")).isEmpty();
            assertThat(OutputKind.parse("")).isEmpty();
            assertThat(OutputKind.parse(null)).isEmpty();
            assertThat(OutputKind.fromCatalogId("laser")).isNull();
        }
    }

    @Nested
    @DisplayName("JSON mapping")
    class JsonMapping {

        private final JsonMapper mapper = JsonMapper.builder().build();

        @Test
        @DisplayName("serializes as the catalog id")
        void should_serializeAsCatalogId() {
            assertThat(mapper.writeValueAsString(OutputKind.LED_SEGMENT)).isEqualTo("\"led_segment\"");
        }

        @Test
        @DisplayName("deserializes from the catalog id")
        void should_deserializeFromCatalogId() {
            assertThat(mapper.readValue("\"sensor\"", OutputKind.class)).isEqualTo(OutputKind.SENSOR);
        }
    }
}
