package com.stocka.backend.modules.contract;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Named positions of a {@code moving_head} port ({@code config.positions}): a list of
 * {@code {name, pan?, tilt?, zoom?, intensity?, color?, white?}} in the units of the {@code set}
 * action. A clip of {@code set} may name one through its {@code position} parameter; the values of
 * the position fill the parameters the clip does not give, and the explicit ones win. Names match
 * trimmed and case-insensitively.
 *
 * @since 2026-11-06
 */
public final class HeadPositions {

    /** Config key holding the positions. */
    public static final String CONFIG_KEY = "positions";
    /** Parameter of {@code set} naming a position. */
    public static final String PARAM = "position";
    /**
     * Parameter of {@code set} naming the position the head starts from: the export writes an immediate
     * {@code set} to it right before the cue, so one clip moves the head from one position to another.
     */
    public static final String FROM_PARAM = "from_position";
    /** Keys of a position besides {@code name}, in the order they are written. */
    public static final List<String> VALUE_KEYS = List.of("pan", "tilt", "zoom", "intensity", "color", "white");

    private HeadPositions() {
    }

    /**
     * The positions declared in a port config, skipping anything that is not an object with a name.
     *
     * @param config port config; may be {@code null}
     * @return the positions, possibly empty
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> of(Map<String, Object> config) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (config == null || !(config.get(CONFIG_KEY) instanceof List<?> list)) {
            return out;
        }
        for (Object entry : list) {
            if (entry instanceof Map<?, ?> m && m.get("name") instanceof String name && !name.isBlank()) {
                out.add((Map<String, Object>) m);
            }
        }
        return out;
    }

    /**
     * Names of the positions of a port config, trimmed, in declaration order.
     *
     * @param config port config; may be {@code null}
     * @return the names
     */
    public static List<String> names(Map<String, Object> config) {
        return of(config).stream().map(p -> ((String) p.get("name")).trim()).toList();
    }

    /**
     * Finds a position by name.
     *
     * @param config port config; may be {@code null}
     * @param name   requested name (trimmed, case-insensitive)
     * @return the position, or empty when the port has none with that name
     */
    public static Optional<Map<String, Object>> find(Map<String, Object> config, String name) {
        if (name == null) {
            return Optional.empty();
        }
        String wanted = normalize(name);
        return of(config).stream().filter(p -> normalize((String) p.get("name")).equals(wanted)).findFirst();
    }

    /**
     * Whether {@code names} contains {@code name} under the matching rule of the positions.
     *
     * @param names position names
     * @param name  requested name
     * @return {@code true} when one matches
     */
    public static boolean contains(List<String> names, String name) {
        if (names == null || name == null) {
            return false;
        }
        String wanted = normalize(name);
        return names.stream().anyMatch(n -> normalize(n).equals(wanted));
    }

    /**
     * The canonical form of a name for matching: trimmed and lower-cased.
     *
     * @param name a position name
     * @return its canonical form
     */
    public static String normalize(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }
}
