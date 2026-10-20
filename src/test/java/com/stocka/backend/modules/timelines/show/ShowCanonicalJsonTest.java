package com.stocka.backend.modules.timelines.show;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import tools.jackson.databind.JsonNode;

@DisplayName("ShowCanonicalJson")
class ShowCanonicalJsonTest {

    private static Map<String, Object> ordered(Object... kv) {
        return Belen2026Fixture.config(kv);
    }

    @Nested
    @DisplayName("canonical form")
    class Canonical {

        @Test
        @DisplayName("sorts object keys recursively and writes no whitespace")
        void sortsKeys() {
            Map<String, Object> value = ordered("b", 1, "a", ordered("z", true, "y", List.of(1, 2)));
            assertThat(ShowCanonicalJson.canonical(value)).isEqualTo("{\"a\":{\"y\":[1,2],\"z\":true},\"b\":1}");
        }

        @Test
        @DisplayName("writes integral floats as integers and keeps fractions")
        void integralFloats() {
            Map<String, Object> value = ordered("x", 10.0, "y", 2.5, "z", new BigDecimal("3.00"), "w", 21L, "v", 0.25f);
            assertThat(ShowCanonicalJson.canonical(value)).isEqualTo("{\"v\":0.25,\"w\":21,\"x\":10,\"y\":2.5,\"z\":3}");
        }

        @Test
        @DisplayName("keeps non-ASCII characters literally")
        void nonAscii() {
            assertThat(ShowCanonicalJson.canonical(List.of("Belén «2026»", "Pulsador Belen")))
                    .isEqualTo("[\"Belén «2026»\",\"Pulsador Belen\"]");
        }

        @Test
        @DisplayName("leaves booleans and nulls untouched")
        void booleansAndNull() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("t", true);
            value.put("f", false);
            value.put("n", null);
            assertThat(ShowCanonicalJson.canonical(value)).isEqualTo("{\"f\":false,\"n\":null,\"t\":true}");
        }

        @Test
        @DisplayName("escapes quotes, backslashes and control characters like JSON.stringify")
        void escapes() {
            assertThat(ShowCanonicalJson.canonical("a\"b\\c\nd\te\u0001"))
                    .isEqualTo("\"a\\\"b\\\\c\\nd\\te\\u0001\"");
        }

        @Test
        @DisplayName("writes empty arrays and objects compactly")
        void empties() {
            assertThat(ShowCanonicalJson.canonical(ordered("a", List.of(), "b", Map.of()))).isEqualTo("{\"a\":[],\"b\":{}}");
        }
    }

    @Nested
    @DisplayName("numbers as JavaScript prints them")
    class Numbers {

        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource({"21, 21", "2.5, 2.5", "100.0, 100", "-0.5, -0.5", "0.00001, 0.00001", "1e21, 1e+21", "1.5e-8, 1.5e-8",
                "12345678.9, 12345678.9"})
        void formats(String input, String expected) {
            assertThat(ShowNumbers.toJsString(new BigDecimal(input).doubleValue())).isEqualTo(expected);
        }

        @Test
        @DisplayName("normalizes integral values to longs and others to doubles")
        void normalize() {
            assertThat(ShowNumbers.normalize(10.0)).isEqualTo(10L);
            assertThat(ShowNumbers.normalize(new BigDecimal("7.50"))).isEqualTo(7.5);
            assertThat(ShowNumbers.normalize(3)).isEqualTo(3L);
            assertThat(ShowNumbers.isInteger(4.0)).isTrue();
            assertThat(ShowNumbers.isInteger(4.5)).isFalse();
            assertThat(ShowNumbers.isInteger("4")).isFalse();
            assertThat(ShowNumbers.isInteger(true)).isFalse();
            assertThat(ShowNumbers.toNumber(" 12 ")).isEqualTo(12L);
            assertThat(ShowNumbers.toNumber("1e3")).isEqualTo(1000L);
            assertThat(ShowNumbers.toNumber("abc")).isNull();
            assertThat(ShowNumbers.toNumber("")).isNull();
            assertThat(ShowNumbers.toNumber(Double.NaN)).isNull();
            assertThat(ShowNumbers.toNumber(true)).isNull();
        }
    }

    @Nested
    @DisplayName("pretty form")
    class Pretty {

        @Test
        @DisplayName("indents two spaces, one element per line, keys in insertion order")
        void indents() {
            Map<String, Object> value = ordered("b", List.of(1, ordered("k", "v")), "a", List.of(), "c", Map.of());
            assertThat(ShowCanonicalJson.pretty(value)).isEqualTo("""
                    {
                      "b": [
                        1,
                        {
                          "k": "v"
                        }
                      ],
                      "a": [],
                      "c": {}
                    }""");
        }

        @Test
        @DisplayName("reproduces the shared sample file byte for byte")
        void sampleRoundTrip() {
            assertThat(ShowCanonicalJson.pretty(Belen2026Fixture.sample()) + "\n").isEqualTo(Belen2026Fixture.sampleText());
        }
    }

    @Nested
    @DisplayName("hash")
    class Hash {

        @Test
        @DisplayName("sha256 of a UTF-8 string")
        void sha256() {
            assertThat(ShowCanonicalJson.sha256Hex("abc"))
                    .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        }

        @Test
        @DisplayName("the sample package hashes to the value shared with the editor and show_hash.py")
        void samplePackage() {
            JsonNode sample = Belen2026Fixture.sample();
            assertThat(ShowCanonicalJson.showHash(sample)).isEqualTo(Belen2026Fixture.HASH);
            assertThat(sample.path("meta").path("hash").asText()).isEqualTo(Belen2026Fixture.HASH);
        }

        @Test
        @DisplayName("the hash input is the six show keys in order, without meta")
        void hashInput() {
            Map<String, Object> input = ShowCanonicalJson.hashInput(Belen2026Fixture.sample());
            assertThat(input.keySet()).containsExactly("board", "mode", "hardware", "timeline", "durationMs", "assets");
            String text = ShowCanonicalJson.canonical(input);
            assertThat(text).startsWith("{\"assets\":{\"audio\":[\"nature.mp3\"]},\"board\":\"rpi4b\"");
            assertThat(text).contains("\"duration\":10,");
            assertThat(text.replaceAll("\"[^\"]*\"", "")).doesNotContainPattern("\\s");
        }
    }
}
