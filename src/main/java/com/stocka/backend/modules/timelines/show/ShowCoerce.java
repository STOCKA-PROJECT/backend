package com.stocka.backend.modules.timelines.show;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.stocka.backend.modules.contract.CatalogParam;
import com.stocka.backend.modules.contract.CatalogType;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns the loosely typed values a scene holds (strings from inputs, numbers, arrays) into the exact
 * JSON the Raspberry Pi controllers expect, following the catalog parameter type. Port of the
 * editor's {@code utils/showCoerce.ts}; the messages are the Catalan texts the editor shows.
 */
public final class ShowCoerce {

    private static final Pattern HEX = Pattern.compile("^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$");
    private static final Pattern TRIPLE =
            Pattern.compile("^\\[?\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})\\s*\\]?$");
    private static final Pattern NUMERIC_LIST_START = Pattern.compile("^\\[\\s*\\d.*", Pattern.DOTALL);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ShowCoerce() {
    }

    /** Why a value was refused. */
    public enum Reason { REQUIRED, TYPE, RANGE, ENUM }

    /**
     * Outcome of {@link #coerceParam}.
     *
     * @param ok       whether the value fits
     * @param value    the coerced value when {@code ok} and not omitted
     * @param omitted  {@code true} for a blank optional, which simply leaves the package
     * @param reason   why it was refused, when not {@code ok}
     * @param expected what the value should have been, for the message
     */
    public record CoerceResult(boolean ok, Object value, boolean omitted, Reason reason, String expected) {

        static CoerceResult of(Object value) {
            return new CoerceResult(true, value, false, null, null);
        }

        static CoerceResult omit() {
            return new CoerceResult(true, null, true, null, null);
        }

        static CoerceResult fail(Reason reason, String expected) {
            return new CoerceResult(false, null, false, reason, expected);
        }
    }

    /**
     * Human label of a catalog type, as the editor words it.
     *
     * @param type catalog type
     * @return e.g. {@code un enter}
     */
    public static String typeLabel(CatalogType type) {
        return switch (type) {
            case INT -> "un enter";
            case NUMBER -> "un número";
            case BOOL -> "un booleà";
            case STRING -> "text";
            case RGB -> "un color [r, g, b]";
            case RGB_LIST -> "una llista de colors";
            case ENUM -> "un valor de la llista";
        };
    }

    /**
     * Parses a colour given as {@code [r,g,b]} (list), {@code "[r,g,b]"}, {@code "r,g,b"},
     * {@code "#rrggbb"} or {@code "#rgb"}.
     *
     * @param value raw value
     * @return the three components, or {@code null} when it is not a colour
     */
    public static List<Integer> parseRgb(Object value) {
        if (value instanceof List<?> list) {
            if (list.size() != 3) {
                return null;
            }
            List<Integer> out = new ArrayList<>(3);
            for (Object c : list) {
                Object component = c instanceof String s && !s.trim().isEmpty() ? ShowNumbers.toNumber(s) : c;
                if (!ShowNumbers.isInteger(component)) {
                    return null;
                }
                long v = ((Number) component).longValue();
                if (v < 0 || v > 255) {
                    return null;
                }
                out.add((int) v);
            }
            return out;
        }
        if (!(value instanceof String s)) {
            return null;
        }
        String text = s.trim();
        Matcher hex = HEX.matcher(text);
        if (hex.matches()) {
            String h = hex.group(1);
            if (h.length() == 3) {
                h = "" + h.charAt(0) + h.charAt(0) + h.charAt(1) + h.charAt(1) + h.charAt(2) + h.charAt(2);
            }
            return List.of(Integer.parseInt(h.substring(0, 2), 16), Integer.parseInt(h.substring(2, 4), 16),
                    Integer.parseInt(h.substring(4, 6), 16));
        }
        Matcher triple = TRIPLE.matcher(text);
        if (!triple.matches()) {
            return null;
        }
        List<Integer> out = new ArrayList<>(3);
        for (int i = 1; i <= 3; i++) {
            int v = Integer.parseInt(triple.group(i));
            if (v > 255) {
                return null;
            }
            out.add(v);
        }
        return out;
    }

    /**
     * Parses a list of colours: a list whose items are colours in any {@link #parseRgb} notation, a
     * JSON array in text form, or colours separated by {@code ;} ({@code #f00;#0f0}). An empty list
     * is valid.
     *
     * @param value raw value
     * @return the colours, or {@code null} when it is not such a list
     */
    public static List<List<Integer>> parseRgbList(Object value) {
        List<?> items;
        if (value instanceof List<?> list) {
            items = list;
        } else if (value instanceof String s) {
            String text = s.trim();
            if (text.isEmpty()) {
                return List.of();
            }
            if (text.startsWith("[") && !NUMERIC_LIST_START.matcher(text).matches()) {
                try {
                    Object parsed = JSON.readValue(text, Object.class);
                    if (!(parsed instanceof List<?> list)) {
                        return null;
                    }
                    items = list;
                } catch (JacksonException e) {
                    return null;
                }
            } else {
                items = List.of(text.split(";", -1));
            }
        } else {
            return null;
        }
        List<List<Integer>> out = new ArrayList<>();
        for (Object item : items) {
            List<Integer> rgb = parseRgb(item);
            if (rgb == null) {
                return null;
            }
            out.add(rgb);
        }
        return out;
    }

    /**
     * Coerces one value against its catalog parameter. Blank optionals are omitted.
     *
     * @param value raw value from the clip or the action definition
     * @param spec  catalog parameter
     * @return the outcome
     */
    public static CoerceResult coerceParam(Object value, CatalogParam spec) {
        // An empty colour list says nothing: treat it like a blank value.
        if (spec.type() == CatalogType.RGB_LIST && value instanceof List<?> list && list.isEmpty()) {
            value = "";
        }
        if (isBlank(value)) {
            return spec.required() ? CoerceResult.fail(Reason.REQUIRED, typeLabel(spec.type())) : CoerceResult.omit();
        }
        switch (spec.type()) {
            case INT -> {
                Number n = ShowNumbers.toNumber(value);
                if (n == null || !ShowNumbers.isInteger(n) || value instanceof Boolean) {
                    return CoerceResult.fail(Reason.TYPE, typeLabel(CatalogType.INT));
                }
                if (!withinBounds(spec, n)) {
                    return CoerceResult.fail(Reason.RANGE, rangeLabel(spec));
                }
                return CoerceResult.of(n);
            }
            case NUMBER -> {
                Number n = ShowNumbers.toNumber(value);
                if (n == null || value instanceof Boolean) {
                    return CoerceResult.fail(Reason.TYPE, typeLabel(CatalogType.NUMBER));
                }
                if (!withinBounds(spec, n)) {
                    return CoerceResult.fail(Reason.RANGE, rangeLabel(spec));
                }
                return CoerceResult.of(n);
            }
            case BOOL -> {
                if (value instanceof Boolean b) {
                    return CoerceResult.of(b);
                }
                if ("true".equals(value)) {
                    return CoerceResult.of(Boolean.TRUE);
                }
                if ("false".equals(value)) {
                    return CoerceResult.of(Boolean.FALSE);
                }
                return CoerceResult.fail(Reason.TYPE, typeLabel(CatalogType.BOOL));
            }
            case STRING -> {
                if (value instanceof String s) {
                    return CoerceResult.of(s.trim());
                }
                if (ShowNumbers.isNumber(value)) {
                    return CoerceResult.of(ShowNumbers.toJsString((Number) value));
                }
                return CoerceResult.fail(Reason.TYPE, typeLabel(CatalogType.STRING));
            }
            case RGB -> {
                List<Integer> rgb = parseRgb(value);
                return rgb != null ? CoerceResult.of(rgb) : CoerceResult.fail(Reason.TYPE, typeLabel(CatalogType.RGB));
            }
            case RGB_LIST -> {
                List<List<Integer>> list = parseRgbList(value);
                if (list == null) {
                    return CoerceResult.fail(Reason.TYPE, typeLabel(CatalogType.RGB_LIST));
                }
                if (list.isEmpty()) {
                    return spec.required() ? CoerceResult.fail(Reason.REQUIRED, typeLabel(CatalogType.RGB_LIST))
                            : CoerceResult.omit();
                }
                return CoerceResult.of(list);
            }
            case ENUM -> {
                if (value instanceof String s && spec.values() != null && spec.values().contains(s)) {
                    return CoerceResult.of(s);
                }
                return CoerceResult.fail(Reason.ENUM, String.join(", ", spec.values() == null ? List.of() : spec.values()));
            }
            default -> throw new IllegalArgumentException("Unknown catalog type " + spec.type());
        }
    }

    /**
     * @param value any value
     * @return whether it is {@code null} or a blank string
     */
    public static boolean isBlank(Object value) {
        return value == null || (value instanceof String s && s.trim().isEmpty());
    }

    private static boolean withinBounds(CatalogParam spec, Number n) {
        BigDecimal value = new BigDecimal(n.toString());
        return (spec.min() == null || value.compareTo(spec.min()) >= 0)
                && (spec.max() == null || value.compareTo(spec.max()) <= 0);
    }

    private static String rangeLabel(CatalogParam spec) {
        String min = spec.min() == null ? "-∞" : ShowNumbers.toJsString(spec.min());
        String max = spec.max() == null ? "∞" : ShowNumbers.toJsString(spec.max());
        return String.format(Locale.ROOT, "entre %s i %s", min, max);
    }
}
