package com.stocka.backend.modules.timelines.show;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Number handling shared by the show builder, so Java values behave like the JavaScript numbers the
 * editor works with: there is a single numeric type, integral values print without a decimal part
 * ({@code 21}, never {@code 21.0}) and strings are parsed the way {@code Number(text)} does.
 */
public final class ShowNumbers {

    private ShowNumbers() {
    }

    /**
     * Parses a value the way {@code Number(value)} does for the inputs the editor produces.
     *
     * @param value a number or a non-blank numeric string; anything else yields {@code null}
     * @return the value normalized with {@link #normalize}, or {@code null} when it is not a finite number
     */
    public static Number toNumber(Object value) {
        if (value instanceof Boolean) {
            return null;
        }
        if (value instanceof Number n) {
            return isFinite(n) ? normalize(n) : null;
        }
        if (value instanceof String s && !s.trim().isEmpty()) {
            try {
                return normalize(new BigDecimal(s.trim()));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Collapses the Java numeric types to the two JavaScript distinguishes when printing: an
     * integral value becomes a {@link Long} (or {@link BigInteger} when it does not fit), anything
     * else a {@link Double}.
     *
     * @param n any number
     * @return the normalized number
     */
    public static Number normalize(Number n) {
        if (n instanceof Long || n instanceof Integer || n instanceof Short || n instanceof Byte) {
            return n.longValue();
        }
        if (n instanceof BigInteger big) {
            return big.bitLength() < 63 ? (Number) big.longValue() : big;
        }
        BigDecimal d = n instanceof BigDecimal bd ? bd : new BigDecimal(n.toString());
        BigDecimal stripped = d.stripTrailingZeros();
        if (stripped.scale() <= 0) {
            BigInteger integer = stripped.toBigIntegerExact();
            return integer.bitLength() < 63 ? (Number) integer.longValue() : integer;
        }
        return d.doubleValue();
    }

    /**
     * @param value any value
     * @return whether it is a finite number with no fractional part ({@code Number.isInteger})
     */
    public static boolean isInteger(Object value) {
        if (!(value instanceof Number n) || value instanceof Boolean || !isFinite(n)) {
            return false;
        }
        return normalize(n) instanceof Long || normalize(n) instanceof BigInteger;
    }

    /**
     * @param value any value
     * @return whether it is a finite number ({@code typeof value === 'number' && Number.isFinite(value)})
     */
    public static boolean isNumber(Object value) {
        return value instanceof Number n && !(value instanceof Boolean) && isFinite(n);
    }

    /**
     * Formats a number the way {@code String(n)} / {@code JSON.stringify(n)} do: integral values
     * without a decimal part, others in the shortest round-trip form, exponent notation only below
     * {@code 1e-7} or from {@code 1e21} on.
     *
     * @param n any finite number
     * @return its JavaScript text
     */
    public static String toJsString(Number n) {
        Number normalized = normalize(n);
        if (normalized instanceof Long || normalized instanceof BigInteger) {
            BigDecimal asDecimal = new BigDecimal(normalized.toString());
            if (asDecimal.abs().compareTo(new BigDecimal("1e21")) < 0) {
                return normalized.toString();
            }
            return exponent(asDecimal);
        }
        double d = normalized.doubleValue();
        String text = Double.toString(d);
        if (!text.contains("E")) {
            return text;
        }
        BigDecimal decimal = new BigDecimal(text);
        double abs = Math.abs(d);
        if (abs >= 1e-7 && abs < 1e21) {
            return decimal.stripTrailingZeros().toPlainString();
        }
        return exponent(decimal);
    }

    /** @return {@code 1.5e-8} / {@code 1e+21}: JavaScript exponent notation */
    private static String exponent(BigDecimal decimal) {
        BigDecimal stripped = decimal.stripTrailingZeros();
        int exponent = stripped.precision() - stripped.scale() - 1;
        BigDecimal mantissa = stripped.movePointLeft(exponent);
        String sign = exponent < 0 ? "-" : "+";
        return mantissa.toPlainString() + "e" + sign + Math.abs(exponent);
    }

    private static boolean isFinite(Number n) {
        if (n instanceof Double d) {
            return !d.isNaN() && !d.isInfinite();
        }
        if (n instanceof Float f) {
            return !f.isNaN() && !f.isInfinite();
        }
        return true;
    }
}
