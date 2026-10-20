package com.stocka.backend.modules.contract;

/**
 * Whether ports of a kind carry a GPIO number in {@code Port.pin}: always ({@code true} in the
 * catalog), optionally ({@code "optional"}: a relay is either on a native GPIO or on a channel of an
 * expander), or never.
 */
public enum PinMode {
    /** The kind never carries a pin (segments, motor, audio, moving heads, expanders). */
    NONE,
    /** The kind carries a pin unless its config addresses it another way (relay: {@code expander} + {@code channel}). */
    OPTIONAL,
    /** The kind always carries a pin (strips, button). */
    REQUIRED;

    /**
     * Parses the catalog value of {@code pin}.
     *
     * @param value {@code true}, {@code false}, {@code "optional"} or {@code null}
     * @return the mode; unknown values count as {@link #NONE}
     */
    public static PinMode parse(Object value) {
        if (Boolean.TRUE.equals(value)) {
            return REQUIRED;
        }
        if (value instanceof String s && "optional".equalsIgnoreCase(s.trim())) {
            return OPTIONAL;
        }
        return NONE;
    }

    /** @return {@code true} when a port of this kind may carry a pin */
    public boolean allowsPin() {
        return this != NONE;
    }
}
