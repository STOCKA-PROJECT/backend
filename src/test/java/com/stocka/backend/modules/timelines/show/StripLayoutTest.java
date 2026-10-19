package com.stocka.backend.modules.timelines.show;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.stocka.backend.modules.pieces.dto.AttributeScope;
import com.stocka.backend.modules.pieces.dto.PieceAttributeValueResponseDto;
import com.stocka.backend.modules.piecetypes.entity.AttributeType;
import com.stocka.backend.modules.timelines.show.StripLayout.StripSlot;

@DisplayName("StripLayout")
class StripLayoutTest {

    private static PieceAttributeValueResponseDto attr(String name, String displayName, AttributeType type, String value) {
        return new PieceAttributeValueResponseDto(1, AttributeScope.TYPE, name, displayName, type, value);
    }

    @Nested
    @DisplayName("ledLengthFromAttributes")
    class LedLength {

        @Test
        @DisplayName("reads a positive numeric length attribute by technical or visible name, accents ignored")
        void reads() {
            assertThat(StripLayout.ledLengthFromAttributes(List.of(attr("longitud", "Longitud", AttributeType.INTEGER, "16")))).isEqualTo(16);
            assertThat(StripLayout.ledLengthFromAttributes(List.of(attr("n_leds", "Nombre de LED", AttributeType.DECIMAL, "28.4")))).isEqualTo(28);
            assertThat(StripLayout.ledLengthFromAttributes(List.of(attr("x", "Longitud", AttributeType.INTEGER, "12")))).isEqualTo(12);
            assertThat(StripLayout.ledLengthFromAttributes(List.of(attr("x", "LONGITUD", AttributeType.PRICE, "7")))).isEqualTo(7);
            assertThat(StripLayout.ledLengthFromAttributes(List.of(attr("pixels", null, AttributeType.INTEGER, "3")))).isEqualTo(3);
        }

        @Test
        @DisplayName("ignores non-numeric, empty, zero or unrelated attributes")
        void ignores() {
            assertThat(StripLayout.ledLengthFromAttributes(List.of(attr("longitud", "Longitud", AttributeType.TEXT, "16")))).isNull();
            assertThat(StripLayout.ledLengthFromAttributes(List.of(attr("longitud", "Longitud", AttributeType.INTEGER, null)))).isNull();
            assertThat(StripLayout.ledLengthFromAttributes(List.of(attr("longitud", "Longitud", AttributeType.INTEGER, "0")))).isNull();
            assertThat(StripLayout.ledLengthFromAttributes(List.of(attr("longitud", "Longitud", AttributeType.INTEGER, "x")))).isNull();
            assertThat(StripLayout.ledLengthFromAttributes(List.of(attr("amplada", "Amplada", AttributeType.INTEGER, "16")))).isNull();
            assertThat(StripLayout.ledLengthFromAttributes(null)).isNull();
            assertThat(StripLayout.ledLengthFromAttributes(List.of())).isNull();
        }

        @Test
        @DisplayName("takes the first matching attribute")
        void first() {
            assertThat(StripLayout.ledLengthFromAttributes(List.of(
                    attr("amplada", "Amplada", AttributeType.INTEGER, "99"),
                    attr("leds", "LEDs", AttributeType.INTEGER, "5"),
                    attr("length", "Length", AttributeType.INTEGER, "8")))).isEqualTo(5);
        }
    }

    @Nested
    @DisplayName("orderItems")
    class Order {

        @Test
        @DisplayName("puts the saved order first and appends newcomers, dropping ids no longer assigned")
        void orders() {
            assertThat(StripLayout.orderItems(List.of("a", "b", "c"), List.of("c", "a"))).containsExactly("c", "a", "b");
            assertThat(StripLayout.orderItems(List.of("a", "b"), List.of("z", "b", "b"))).containsExactly("b", "a");
            assertThat(StripLayout.orderItems(List.of("a", "b"), null)).containsExactly("a", "b");
            assertThat(StripLayout.orderItems(List.of(), List.of("a"))).isEmpty();
        }
    }

    @Nested
    @DisplayName("layoutStrip")
    class Layout {

        private final Map<String, Integer> lengths = Map.of("a", 16, "b", 28);

        @Test
        @DisplayName("lays pieces one after the other by their lengths")
        void lays() {
            Map<String, StripSlot> layout = StripLayout.layoutStrip(List.of("a", "b"), lengths::get, 300);
            assertThat(layout.get("a")).isEqualTo(new StripSlot(0, 16, true));
            assertThat(layout.get("b")).isEqualTo(new StripSlot(16, 28, true));
        }

        @Test
        @DisplayName("gives a piece without length the rest of the strip")
        void rest() {
            Map<String, StripSlot> layout = StripLayout.layoutStrip(List.of("a", "c"), lengths::get, 44);
            assertThat(layout.get("c")).isEqualTo(new StripSlot(16, 28, false));
            Map<String, StripSlot> bad = StripLayout.layoutStrip(List.of("c", "a"), lengths::get, 44);
            assertThat(bad.get("c")).isEqualTo(new StripSlot(0, 44, false));
            assertThat(bad.get("a")).isEqualTo(new StripSlot(44, 16, true));
        }

        @Test
        @DisplayName("handles an unknown strip length and an empty strip")
        void unknown() {
            assertThat(StripLayout.layoutStrip(List.of("c"), id -> null, null).get("c")).isEqualTo(new StripSlot(0, 0, false));
            assertThat(StripLayout.layoutStrip(List.of(), id -> null, 10)).isEmpty();
        }
    }

    @Nested
    @DisplayName("uniqueName")
    class Unique {

        @Test
        @DisplayName("suffixes #2, #3… while the name is taken and registers the result")
        void suffixes() {
            Set<String> taken = new HashSet<>(Set.of("Focos", "Focos #2"));
            assertThat(StripLayout.uniqueName("Casa", taken)).isEqualTo("Casa");
            assertThat(StripLayout.uniqueName("Focos", taken)).isEqualTo("Focos #3");
            assertThat(taken).contains("Casa", "Focos #3");
            assertThat(StripLayout.uniqueName("Casa", taken)).isEqualTo("Casa #2");
        }
    }
}
