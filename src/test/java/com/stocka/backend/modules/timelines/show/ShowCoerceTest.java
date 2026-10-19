package com.stocka.backend.modules.timelines.show;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.stocka.backend.modules.contract.CatalogParam;
import com.stocka.backend.modules.contract.CatalogType;
import com.stocka.backend.modules.timelines.show.ShowCoerce.CoerceResult;
import com.stocka.backend.modules.timelines.show.ShowCoerce.Reason;

@DisplayName("ShowCoerce")
class ShowCoerceTest {

    static CatalogParam spec(CatalogType type) {
        return spec(type, false, null, null, List.of());
    }

    static CatalogParam spec(CatalogType type, boolean required, String min, String max, List<String> values) {
        return new CatalogParam("p", type, required, min == null ? null : new BigDecimal(min),
                max == null ? null : new BigDecimal(max), values, false, null, null, null);
    }

    @Nested
    @DisplayName("parseRgb")
    class ParseRgb {

        static Stream<Arguments> accepted() {
            return Stream.of(
                    Arguments.of(List.of(255, 50, 0), List.of(255, 50, 0)),
                    Arguments.of(List.of("255", "50", "0"), List.of(255, 50, 0)),
                    Arguments.of(List.of(255L, 50.0, 0), List.of(255, 50, 0)),
                    Arguments.of("[255,50,0]", List.of(255, 50, 0)),
                    Arguments.of("[255, 50, 0]", List.of(255, 50, 0)),
                    Arguments.of("255,50,0", List.of(255, 50, 0)),
                    Arguments.of(" 255 , 50 , 0 ", List.of(255, 50, 0)),
                    Arguments.of("#ff3200", List.of(255, 50, 0)),
                    Arguments.of("#FF3200", List.of(255, 50, 0)),
                    Arguments.of("#f30", List.of(255, 51, 0)));
        }

        @ParameterizedTest(name = "parses {0}")
        @MethodSource("accepted")
        void parses(Object value, List<Integer> expected) {
            assertThat(ShowCoerce.parseRgb(value)).isEqualTo(expected);
        }

        static Stream<Object> rejected() {
            return Stream.of("vermell", "[256,0,0]", "[1,2]", "#ggg", "#12345", "1,2,3,4", "", List.of(1, 2),
                    List.of(1, 2, 300), List.of(1.5, 2, 3), List.of(true, 2, 3), 42);
        }

        @ParameterizedTest(name = "rejects {0}")
        @MethodSource("rejected")
        void rejects(Object value) {
            assertThat(ShowCoerce.parseRgb(value)).isNull();
        }

        @Test
        @DisplayName("rejects null")
        void rejectsNull() {
            assertThat(ShowCoerce.parseRgb(null)).isNull();
        }
    }

    @Nested
    @DisplayName("parseRgbList")
    class ParseRgbList {

        @Test
        @DisplayName("accepts lists, JSON text and semicolon-separated colours")
        void accepts() {
            List<List<Integer>> expected = List.of(List.of(255, 0, 0), List.of(0, 255, 0));
            assertThat(ShowCoerce.parseRgbList(List.of("#f00", List.of(0, 255, 0)))).isEqualTo(expected);
            assertThat(ShowCoerce.parseRgbList("[[255,0,0],\"#0f0\"]")).isEqualTo(expected);
            assertThat(ShowCoerce.parseRgbList("#f00;#0f0")).isEqualTo(expected);
            assertThat(ShowCoerce.parseRgbList("[255,0,0];[0,255,0]")).isEqualTo(expected);
            assertThat(ShowCoerce.parseRgbList("")).isEmpty();
            assertThat(ShowCoerce.parseRgbList(List.of())).isEmpty();
        }

        @Test
        @DisplayName("rejects malformed lists and non-colours")
        void rejects() {
            assertThat(ShowCoerce.parseRgbList("[[255,0,0],")).isNull();
            assertThat(ShowCoerce.parseRgbList("[\"x\"]")).isNull();
            assertThat(ShowCoerce.parseRgbList("{\"a\":1}")).isNull();
            assertThat(ShowCoerce.parseRgbList("#f00;vermell")).isNull();
            assertThat(ShowCoerce.parseRgbList(List.of("x"))).isNull();
            assertThat(ShowCoerce.parseRgbList(3)).isNull();
            assertThat(ShowCoerce.parseRgbList(null)).isNull();
        }
    }

    @Nested
    @DisplayName("coerceParam")
    class Coerce {

        @Test
        @DisplayName("omits blank optionals and requires blank mandatory values")
        void blanks() {
            assertThat(ShowCoerce.coerceParam(null, spec(CatalogType.INT)).omitted()).isTrue();
            assertThat(ShowCoerce.coerceParam("", spec(CatalogType.STRING)).omitted()).isTrue();
            assertThat(ShowCoerce.coerceParam("   ", spec(CatalogType.STRING)).omitted()).isTrue();
            CoerceResult required = ShowCoerce.coerceParam(null, spec(CatalogType.RGB, true, null, null, List.of()));
            assertThat(required.ok()).isFalse();
            assertThat(required.reason()).isEqualTo(Reason.REQUIRED);
            assertThat(required.expected()).isEqualTo("un color [r, g, b]");
        }

        @Test
        @DisplayName("int accepts integral numbers and numeric strings, within bounds")
        void ints() {
            assertThat(ShowCoerce.coerceParam(3, spec(CatalogType.INT)).value()).isEqualTo(3L);
            assertThat(ShowCoerce.coerceParam("3", spec(CatalogType.INT)).value()).isEqualTo(3L);
            assertThat(ShowCoerce.coerceParam(3.0, spec(CatalogType.INT)).value()).isEqualTo(3L);
            assertThat(ShowCoerce.coerceParam(3.5, spec(CatalogType.INT)).reason()).isEqualTo(Reason.TYPE);
            assertThat(ShowCoerce.coerceParam("x", spec(CatalogType.INT)).reason()).isEqualTo(Reason.TYPE);
            assertThat(ShowCoerce.coerceParam(true, spec(CatalogType.INT)).reason()).isEqualTo(Reason.TYPE);
            CoerceResult range = ShowCoerce.coerceParam(5, spec(CatalogType.INT, false, "0", "4", List.of()));
            assertThat(range.reason()).isEqualTo(Reason.RANGE);
            assertThat(range.expected()).isEqualTo("entre 0 i 4");
            assertThat(ShowCoerce.coerceParam(-1, spec(CatalogType.INT, false, "0", null, List.of())).expected())
                    .isEqualTo("entre 0 i ∞");
        }

        @Test
        @DisplayName("number accepts any finite number and keeps fractions")
        void numbers() {
            assertThat(ShowCoerce.coerceParam(2.5, spec(CatalogType.NUMBER)).value()).isEqualTo(2.5);
            assertThat(ShowCoerce.coerceParam("2.5", spec(CatalogType.NUMBER)).value()).isEqualTo(2.5);
            assertThat(ShowCoerce.coerceParam(10.0, spec(CatalogType.NUMBER)).value()).isEqualTo(10L);
            assertThat(ShowCoerce.coerceParam("abc", spec(CatalogType.NUMBER)).reason()).isEqualTo(Reason.TYPE);
            assertThat(ShowCoerce.coerceParam(false, spec(CatalogType.NUMBER)).reason()).isEqualTo(Reason.TYPE);
            assertThat(ShowCoerce.coerceParam(-1, spec(CatalogType.NUMBER, false, "0", null, List.of())).reason())
                    .isEqualTo(Reason.RANGE);
        }

        @Test
        @DisplayName("bool accepts booleans and the strings true/false only")
        void bools() {
            assertThat(ShowCoerce.coerceParam(true, spec(CatalogType.BOOL)).value()).isEqualTo(true);
            assertThat(ShowCoerce.coerceParam("true", spec(CatalogType.BOOL)).value()).isEqualTo(true);
            assertThat(ShowCoerce.coerceParam("false", spec(CatalogType.BOOL)).value()).isEqualTo(false);
            assertThat(ShowCoerce.coerceParam("yes", spec(CatalogType.BOOL)).reason()).isEqualTo(Reason.TYPE);
            assertThat(ShowCoerce.coerceParam(1, spec(CatalogType.BOOL)).reason()).isEqualTo(Reason.TYPE);
        }

        @Test
        @DisplayName("string trims text and stringifies numbers")
        void strings() {
            assertThat(ShowCoerce.coerceParam("  nature.mp3 ", spec(CatalogType.STRING)).value()).isEqualTo("nature.mp3");
            assertThat(ShowCoerce.coerceParam(12, spec(CatalogType.STRING)).value()).isEqualTo("12");
            assertThat(ShowCoerce.coerceParam(1.5, spec(CatalogType.STRING)).value()).isEqualTo("1.5");
            assertThat(ShowCoerce.coerceParam(List.of(1), spec(CatalogType.STRING)).reason()).isEqualTo(Reason.TYPE);
            assertThat(ShowCoerce.coerceParam(true, spec(CatalogType.STRING)).reason()).isEqualTo(Reason.TYPE);
        }

        @Test
        @DisplayName("rgb accepts every colour notation")
        void rgb() {
            assertThat(ShowCoerce.coerceParam("#0a003c", spec(CatalogType.RGB)).value()).isEqualTo(List.of(10, 0, 60));
            assertThat(ShowCoerce.coerceParam("vermell", spec(CatalogType.RGB)).reason()).isEqualTo(Reason.TYPE);
        }

        @Test
        @DisplayName("rgb_list accepts colour lists, omits empty optional ones and requires empty mandatory ones")
        void rgbList() {
            assertThat(ShowCoerce.coerceParam("#f00;#0f0", spec(CatalogType.RGB_LIST)).value())
                    .isEqualTo(List.of(List.of(255, 0, 0), List.of(0, 255, 0)));
            assertThat(ShowCoerce.coerceParam(List.of(), spec(CatalogType.RGB_LIST)).omitted()).isTrue();
            assertThat(ShowCoerce.coerceParam("", spec(CatalogType.RGB_LIST, true, null, null, List.of())).reason())
                    .isEqualTo(Reason.REQUIRED);
            assertThat(ShowCoerce.coerceParam("x", spec(CatalogType.RGB_LIST)).reason()).isEqualTo(Reason.TYPE);
        }

        @Test
        @DisplayName("enum accepts one of the listed values")
        void enums() {
            CatalogParam spec = spec(CatalogType.ENUM, true, null, null, List.of("fade", "fire"));
            assertThat(ShowCoerce.coerceParam("fade", spec).value()).isEqualTo("fade");
            CoerceResult bad = ShowCoerce.coerceParam("snow", spec);
            assertThat(bad.reason()).isEqualTo(Reason.ENUM);
            assertThat(bad.expected()).isEqualTo("fade, fire");
            assertThat(ShowCoerce.coerceParam(1, spec).reason()).isEqualTo(Reason.ENUM);
        }
    }
}
