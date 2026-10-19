package com.stocka.backend.modules.timelines.show;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import com.stocka.backend.modules.pieces.dto.PieceAttributeValueResponseDto;
import com.stocka.backend.modules.piecetypes.entity.AttributeType;

/**
 * Pieces on a LED strip: several pieces share one {@code led_strip} port, laid out one after the
 * other along the strip in the order the scene keeps per port. Each piece takes as many LEDs as its
 * own length attribute says; the export turns every piece into a segment of the strip (first/last
 * absolute) so the player addresses them by name. Port of the editor's {@code utils/stripLayout.ts}.
 */
public final class StripLayout {

    /** Piece attributes that give the number of LEDs of the figure (technical or visible name). */
    static final Set<String> LED_COUNT_ATTRS = Set.of("leds", "led", "num_leds", "n_leds", "led_count", "longitud",
            "length", "pixels", "nombre_de_led", "nombre de led");
    private static final Set<AttributeType> NUMERIC_TYPES =
            Set.of(AttributeType.INTEGER, AttributeType.DECIMAL, AttributeType.PRICE);

    private StripLayout() {
    }

    /**
     * Position of one item along a strip.
     *
     * @param offset   first LED of the piece
     * @param length   LEDs of the piece; a piece without length takes what is left of the strip (0 when none)
     * @param explicit whether the length came from the piece (false: the rest of the strip)
     */
    public record StripSlot(int offset, int length, boolean explicit) {
    }

    /**
     * LEDs of a piece according to its numeric length attribute (e.g. «Longitud»).
     *
     * @param values the piece's attribute values (type and organization scope); may be {@code null}
     * @return the LED count, or {@code null} when the piece has none
     */
    public static Integer ledLengthFromAttributes(List<PieceAttributeValueResponseDto> values) {
        if (values == null) {
            return null;
        }
        for (PieceAttributeValueResponseDto a : values) {
            if (a.type() == null || !NUMERIC_TYPES.contains(a.type()) || a.value() == null) {
                continue;
            }
            if (LED_COUNT_ATTRS.contains(normalize(a.attributeName())) || LED_COUNT_ATTRS.contains(normalize(a.displayName()))) {
                Number n = ShowNumbers.toNumber(a.value());
                if (n != null) {
                    long rounded = Math.round(n.doubleValue());
                    if (rounded > 0) {
                        return (int) rounded;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Items assigned to a port in strip order: the ones the saved order knows first, then the rest
     * as given.
     *
     * @param assigned item ids assigned to the port, in document order
     * @param order    saved order for the port; may be {@code null}
     * @return the ordered ids
     */
    public static List<String> orderItems(List<String> assigned, List<String> order) {
        Set<String> set = new LinkedHashSet<>(assigned);
        Set<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        if (order != null) {
            for (String id : order) {
                if (set.contains(id) && seen.add(id)) {
                    out.add(id);
                }
            }
        }
        for (String id : assigned) {
            if (!seen.contains(id)) {
                out.add(id);
            }
        }
        return out;
    }

    /**
     * Position of every item along a strip of {@code stripLength} LEDs, in the given order.
     *
     * @param itemIds     items in strip order
     * @param lengthOf    LEDs of an item, or {@code null} when unknown
     * @param stripLength LEDs of the strip, or {@code null} when unknown
     * @return the slot of every item, in order
     */
    public static Map<String, StripSlot> layoutStrip(List<String> itemIds, Function<String, Integer> lengthOf,
                                                     Integer stripLength) {
        Map<String, StripSlot> out = new LinkedHashMap<>();
        int offset = 0;
        for (String id : itemIds) {
            Integer own = lengthOf.apply(id);
            int length = own != null ? own : Math.max(0, (stripLength == null ? 0 : stripLength) - offset);
            out.put(id, new StripSlot(offset, length, own != null));
            offset += length;
        }
        return out;
    }

    /**
     * {@code base}, or {@code base #2}, {@code base #3}… until it is not in {@code taken} (which it
     * then joins).
     *
     * @param base  wanted name
     * @param taken names already used; the returned name is added to it
     * @return the unique name
     */
    public static String uniqueName(String base, Set<String> taken) {
        String name = base;
        for (int n = 2; taken.contains(name); n++) {
            name = base + " #" + n;
        }
        taken.add(name);
        return name;
    }

    /** @return lower case, without diacritics, trimmed; empty for {@code null} */
    static String normalize(String s) {
        if (s == null) {
            return "";
        }
        return Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .trim();
    }
}
