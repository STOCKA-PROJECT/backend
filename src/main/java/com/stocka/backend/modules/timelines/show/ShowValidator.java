package com.stocka.backend.modules.timelines.show;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.stocka.backend.modules.contract.BoardSpec;
import com.stocka.backend.modules.contract.CatalogAction;
import com.stocka.backend.modules.contract.CatalogKind;
import com.stocka.backend.modules.contract.CatalogParam;
import com.stocka.backend.modules.contract.OutputCatalog;
import com.stocka.backend.modules.contract.OutputKind;
import com.stocka.backend.modules.timelines.show.ShowPackage.ShowAssets;
import com.stocka.backend.modules.timelines.show.ShowPackage.ShowEvent;

/**
 * Semantic rules of a {@code stocka-show} package, mirroring {@code rpi4-system/contract/validate.py}
 * and the editor's {@code utils/showValidate.ts}: every target exists, every action belongs to the
 * kind of its port, parameters are the catalog's, pins do not clash and segments fit their strip.
 * Runs on the assembled package, so the device will accept what passes here.
 */
public final class ShowValidator {

    private ShowValidator() {
    }

    /**
     * Strict type check of a value already in package form (no coercion: the device reads it as is).
     *
     * @param value value as it sits in the package
     * @param spec  catalog parameter
     * @return the problem, or {@code null} when the value fits
     */
    public static String checkValue(Object value, CatalogParam spec) {
        switch (spec.type()) {
            case INT -> {
                if (!ShowNumbers.isInteger(value)) return "ha de ser un enter";
            }
            case NUMBER -> {
                if (!ShowNumbers.isNumber(value)) return "ha de ser un número";
            }
            case BOOL -> {
                return value instanceof Boolean ? null : "ha de ser booleà";
            }
            case STRING -> {
                return value instanceof String ? null : "ha de ser text";
            }
            case RGB -> {
                return isRgb(value) ? null : "ha de ser un color [r, g, b] amb valors de 0 a 255";
            }
            case RGB_LIST -> {
                if (!(value instanceof List<?> list) || !list.stream().allMatch(ShowValidator::isRgb)) {
                    return "ha de ser una llista de colors [[r, g, b], …] amb valors de 0 a 255";
                }
                return null;
            }
            case ENUM -> {
                if (!(value instanceof String s) || spec.values() == null || !spec.values().contains(s)) {
                    return "ha de ser un de: " + String.join(", ", spec.values() == null ? List.of() : spec.values());
                }
                return null;
            }
            default -> {
                return null;
            }
        }
        BigDecimal n = new BigDecimal(ShowNumbers.normalize((Number) value).toString());
        if (spec.min() != null && n.compareTo(spec.min()) < 0) return "ha de ser com a mínim " + ShowNumbers.toJsString(spec.min());
        if (spec.max() != null && n.compareTo(spec.max()) > 0) return "ha de ser com a màxim " + ShowNumbers.toJsString(spec.max());
        return null;
    }

    /** @return whether {@code value} is {@code [r, g, b]} with three integers in 0–255 */
    static boolean isRgb(Object value) {
        if (!(value instanceof List<?> list) || list.size() != 3) {
            return false;
        }
        for (Object c : list) {
            if (!ShowNumbers.isInteger(c)) return false;
            long v = ((Number) c).longValue();
            if (v < 0 || v > 255) return false;
        }
        return true;
    }

    /**
     * Validates an assembled package.
     *
     * @param hardware   hardware entries by name
     * @param timeline   events
     * @param board      board id
     * @param durationMs show length
     * @param assets     declared assets
     * @param catalog    output catalog
     * @return every finding, in rule order
     */
    public static List<ShowIssue> validate(Map<String, Map<String, Object>> hardware, List<ShowEvent> timeline,
                                           String board, long durationMs, ShowAssets assets, OutputCatalog catalog) {
        List<ShowIssue> issues = new ArrayList<>();
        BoardSpec boardSpec = catalog.board(board).orElse(null);
        boolean hasAudio = hardware.values().stream().anyMatch(e -> "audio".equals(e.get("kind")));

        // --- hardware: kinds, config, pins -------------------------------------------------------
        Map<Long, List<String>> usedPins = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> h : hardware.entrySet()) {
            String name = h.getKey();
            Map<String, Object> entry = h.getValue();
            CatalogKind spec = kindOf(entry, catalog);
            if (spec == null) {
                issues.add(ShowIssue.error("E_KIND_UNKNOWN", "El port «" + name + "» té un kind desconegut: "
                        + entry.get("kind") + ".", null, name));
                continue;
            }
            for (Map.Entry<String, CatalogParam> field : spec.config().entrySet()) {
                if (!entry.containsKey(field.getKey())) {
                    if (field.getValue().required()) {
                        issues.add(ShowIssue.error("E_CONFIG_INVALID", "El port «" + name + "» necessita «"
                                + field.getKey() + "».", null, name));
                    }
                    continue;
                }
                String why = checkValue(entry.get(field.getKey()), field.getValue());
                if (why != null) {
                    issues.add(ShowIssue.error("E_CONFIG_INVALID", "El port «" + name + "»: «" + field.getKey() + "» "
                            + why + ".", null, name));
                }
            }
            if (spec.pin() && !ShowNumbers.isNumber(entry.get("pin"))) {
                issues.add(ShowIssue.error("E_CONFIG_INVALID", "El port «" + name + "» necessita «pin».", null, name));
            }
            for (Map.Entry<String, Long> pinOf : pinsOf(name, entry, spec.pin())) {
                long pin = pinOf.getValue();
                String label = pinOf.getKey();
                usedPins.computeIfAbsent(pin, k -> new ArrayList<>()).add(label);
                if (boardSpec != null) {
                    if (pin < boardSpec.gpioMin() || pin > boardSpec.gpioMax()) {
                        issues.add(ShowIssue.error("E_PIN_OUT_OF_BOARD", "El pin " + pin + " de «" + label + "» no existeix a "
                                + board + " (" + boardSpec.gpioMin() + "–" + boardSpec.gpioMax() + ").", null, name));
                    } else if (boardSpec.reserved().containsKey((int) pin)) {
                        issues.add(ShowIssue.error("E_PIN_RESERVED", "El pin " + pin + " de «" + label + "» està reservat: "
                                + boardSpec.reserved().get((int) pin) + ".", null, name));
                    } else if (boardSpec.warnings().containsKey((int) pin)) {
                        issues.add(ShowIssue.warning("W_PIN_PULLUP", "El pin " + pin + " de «" + label + "»: "
                                + boardSpec.warnings().get((int) pin) + ".", null, name));
                    }
                }
            }
            if (spec.kind() == OutputKind.LED_STRIP && ShowNumbers.isNumber(entry.get("pin")) && boardSpec != null) {
                int pin = ((Number) entry.get("pin")).intValue();
                Integer channel = boardSpec.ws281xChannel().get(pin);
                if (channel == null) {
                    issues.add(ShowIssue.error("E_WS281X_PIN", "El pin " + pin + " de la tira «" + name
                            + "» no admet WS281x a " + board + ".", null, name));
                } else {
                    if (!sameNumber(entry.get("channel"), channel)) {
                        issues.add(ShowIssue.error("E_WS281X_CHANNEL", "La tira «" + name + "» al pin " + pin
                                + " ha d'usar channel " + channel + ".", null, name));
                    }
                    String warning = boardSpec.ws281xWarning(pin);
                    if (warning != null && hasAudio) {
                        issues.add(ShowIssue.warning("W_PCM_AUDIO", "La tira «" + name + "» al pin " + pin + ": "
                                + warning + ".", null, name));
                    }
                }
                if (ShowNumbers.isNumber(entry.get("dma")) && boardSpec.dmaAvoid().contains(((Number) entry.get("dma")).intValue())) {
                    List<String> avoid = boardSpec.dmaAvoid().stream().map(String::valueOf).toList();
                    issues.add(ShowIssue.warning("W_DMA_AVOID", "La tira «" + name + "» usa DMA "
                            + ShowNumbers.toJsString((Number) entry.get("dma")) + ", no recomanat (evita "
                            + String.join(", ", avoid) + ").", null, name));
                }
            }
            if (spec.kind() == OutputKind.MOTOR) {
                List<Long> pins = new ArrayList<>();
                for (String key : List.of("pinR", "pinL", "sensorPin")) {
                    if (ShowNumbers.isNumber(entry.get(key))) pins.add(((Number) entry.get(key)).longValue());
                }
                if (new HashSet<>(pins).size() != pins.size()) {
                    issues.add(ShowIssue.error("E_CONFIG_INVALID", "El motor «" + name
                            + "» repeteix un pin entre pinR, pinL i sensorPin.", null, name));
                }
            }
        }
        for (Map.Entry<Long, List<String>> used : usedPins.entrySet()) {
            if (used.getValue().size() > 1) {
                issues.add(ShowIssue.error("E_PIN_DUPLICATE", "El pin " + used.getKey() + " està usat per més d'un port: "
                        + String.join(", ", used.getValue()) + "."));
            }
        }

        // --- segments ----------------------------------------------------------------------------
        // Whole strips are show targets too (fill/animate on the entire strip): their length bounds first/last.
        Map<String, Long> segmentLength = new HashMap<>();
        for (Map.Entry<String, Map<String, Object>> h : hardware.entrySet()) {
            String name = h.getKey();
            Map<String, Object> entry = h.getValue();
            if ("led_strip".equals(entry.get("kind")) && ShowNumbers.isNumber(entry.get("length"))) {
                segmentLength.put(name, ((Number) entry.get("length")).longValue());
            }
            if (!"led_segment".equals(entry.get("kind"))) {
                continue;
            }
            Map<String, Object> strip = hardware.get(String.valueOf(entry.get("strip")));
            if (strip == null || !"led_strip".equals(strip.get("kind"))) {
                issues.add(ShowIssue.error("E_SEGMENT_STRIP_MISSING", "El segment «" + name + "» referencia la tira «"
                        + entry.get("strip") + "», que no existeix com a led_strip.", null, name));
                continue;
            }
            Object first = entry.get("first");
            Object last = entry.get("last");
            Object length = strip.get("length");
            if (ShowNumbers.isNumber(first) && ShowNumbers.isNumber(last) && ShowNumbers.isNumber(length)) {
                long f = ((Number) first).longValue();
                long l = ((Number) last).longValue();
                long len = ((Number) length).longValue();
                if (Math.min(f, l) < 0 || Math.max(f, l) > len - 1) {
                    issues.add(ShowIssue.error("E_SEGMENT_RANGE", "El segment «" + name + "» (" + f + "–" + l
                            + ") surt de la tira «" + entry.get("strip") + "» (0–" + (len - 1) + ").", null, name));
                }
                segmentLength.put(name, Math.abs(l - f) + 1);
            }
        }

        // --- timeline ----------------------------------------------------------------------------
        ShowAssets declared = assets == null ? new ShowAssets(List.of(), List.of()) : assets;
        Map<String, Set<String>> referencedAssets = new HashMap<>();
        Set<String> usedTargets = new HashSet<>();
        Map<String, List<double[]>> continuous = new LinkedHashMap<>();
        for (int idx = 0; idx < timeline.size(); idx++) {
            ShowEvent ev = timeline.get(idx);
            String where = "esdeveniment " + (idx + 1) + " (t=" + ev.t() + " ms)";
            Map<String, Object> entry = hardware.get(ev.target());
            if (entry == null) {
                issues.add(ShowIssue.error("E_TARGET_MISSING", where + ": el port «" + ev.target() + "» no és a hardware."));
                continue;
            }
            usedTargets.add(ev.target());
            if (ev.t() > durationMs) {
                issues.add(ShowIssue.error("E_T_AFTER_END", where + ": comença després del final del show ("
                        + durationMs + " ms).", null, ev.target()));
            }
            CatalogKind spec = kindOf(entry, catalog);
            if (spec == null) continue;
            CatalogAction action = spec.actions().get(ev.action());
            if (action == null) {
                issues.add(ShowIssue.error("E_ACTION_UNKNOWN", where + ": l'acció «" + ev.action()
                        + "» no existeix per a sortides " + entry.get("kind") + " («" + ev.target() + "»).", null, ev.target()));
                continue;
            }
            Map<String, Object> params = ev.params() == null ? Map.of() : ev.params();
            String animation = !action.animations().isEmpty() && params.get("animation_type") instanceof String a ? a : null;
            Map<String, CatalogParam> allowed = animation != null ? action.paramsFor(animation) : action.allParams();
            for (Map.Entry<String, Object> p : params.entrySet()) {
                String pname = p.getKey();
                CatalogParam pspec = allowed.get(pname);
                if (pspec == null) {
                    String code = animation != null ? "E_ANIMATION_PARAM" : "E_PARAM_UNKNOWN";
                    issues.add(ShowIssue.error(code, where + ": el paràmetre «" + pname + "» no existeix a "
                            + entry.get("kind") + "." + ev.action()
                            + (animation != null ? " per a l'animació «" + animation + "»" : "") + ".", null, ev.target()));
                    continue;
                }
                String why = checkValue(p.getValue(), pspec);
                if (why != null) {
                    issues.add(ShowIssue.error("E_PARAM_TYPE", where + ": el paràmetre «" + pname + "» " + why + ".",
                            null, ev.target()));
                }
                if (pspec.asset() != null && p.getValue() instanceof String assetName) {
                    referencedAssets.computeIfAbsent(pspec.asset(), k -> new LinkedHashSet<>()).add(assetName);
                    if (!declaredOf(declared, pspec.asset()).contains(assetName)) {
                        issues.add(ShowIssue.error("E_ASSET_UNLISTED", where + ": el fitxer «" + assetName
                                + "» no és a assets." + pspec.asset() + ".", null, ev.target()));
                    }
                }
            }
            for (String pname : requiredParams(action, animation)) {
                if (!params.containsKey(pname)) {
                    issues.add(ShowIssue.error("E_PARAM_REQUIRED", where + ": falta el paràmetre obligatori «" + pname
                            + "».", null, ev.target()));
                }
            }
            Long segLen = segmentLength.get(ev.target());
            if ((spec.kind() == OutputKind.LED_SEGMENT || spec.kind() == OutputKind.LED_STRIP) && segLen != null) {
                for (String key : List.of("first", "last")) {
                    Object v = params.get(key);
                    if (ShowNumbers.isNumber(v)) {
                        double d = ((Number) v).doubleValue();
                        if (!(d >= 0 && d < segLen)) {
                            issues.add(ShowIssue.error("E_SEGMENT_RANGE", where + ": «" + key + "»="
                                    + ShowNumbers.toJsString((Number) v) + " surt de «" + ev.target() + "» (0–"
                                    + (segLen - 1) + ").", null, ev.target()));
                        }
                    }
                }
            }
            if ("animate".equals(ev.action()) && "fade".equals(animation) && !params.containsKey("from_color")) {
                issues.add(ShowIssue.warning("W_FADE_FROM_BLACK", where + ": el fos a «" + ev.target()
                        + "» no indica from_color: començarà des de negre.", null, ev.target()));
            }
            if (action.continuous()) {
                Object d = params.get("duration");
                double duration = ShowNumbers.isNumber(d) ? ((Number) d).doubleValue() : Double.NaN;
                continuous.computeIfAbsent(ev.target(), k -> new ArrayList<>()).add(new double[] {ev.t(), duration});
            }
        }

        for (Map.Entry<String, List<double[]>> c : continuous.entrySet()) {
            List<double[]> events = c.getValue();
            events.sort((a, b) -> Double.compare(a[0], b[0]));
            for (int i = 1; i < events.size(); i++) {
                double t1 = events.get(i - 1)[0];
                double d1 = events.get(i - 1)[1];
                double t2 = events.get(i)[0];
                if (Double.isNaN(d1) || t1 + d1 * 1000 > t2) {
                    issues.add(ShowIssue.warning("W_OVERLAP_CONTINUOUS", "Dues animacions se solapen a «" + c.getKey()
                            + "»: " + (long) t1 + " ms i " + (long) t2 + " ms.", null, c.getKey()));
                }
            }
        }

        Map<String, List<String>> singletons = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> h : hardware.entrySet()) {
            CatalogKind spec = kindOf(h.getValue(), catalog);
            if (spec == null) continue;
            if (!spec.actions().isEmpty() && !spec.infrastructure() && !usedTargets.contains(h.getKey())) {
                issues.add(ShowIssue.warning("W_PORT_UNUSED", "El port «" + h.getKey() + "» no té cap esdeveniment.",
                        null, h.getKey()));
            }
            if (spec.kind() == OutputKind.MOTOR || spec.kind() == OutputKind.AUDIO || spec.kind() == OutputKind.VIDEO) {
                singletons.computeIfAbsent(spec.kind().catalogId(), k -> new ArrayList<>()).add(h.getKey());
            }
        }
        for (Map.Entry<String, List<String>> s : singletons.entrySet()) {
            if (s.getValue().size() > 1) {
                issues.add(ShowIssue.warning("W_LEGACY_SINGLE", "El reproductor actual només admet un port de tipus "
                        + s.getKey() + "; n'hi ha " + s.getValue().size() + ": " + String.join(", ", s.getValue()) + "."));
            }
        }
        for (String assetKind : List.of("audio", "video")) {
            for (String n : declaredOf(declared, assetKind)) {
                if (!referencedAssets.getOrDefault(assetKind, Set.of()).contains(n)) {
                    issues.add(ShowIssue.warning("W_ASSET_UNUSED", "El fitxer «" + n + "» és a assets." + assetKind
                            + " però cap esdeveniment l'usa."));
                }
            }
        }
        return issues;
    }

    /**
     * Parameters that must be present: the common required ones plus the fixed animation's.
     *
     * @param action    catalog action
     * @param animation animation id, or {@code null}
     * @return the required parameter names
     */
    static List<String> requiredParams(CatalogAction action, String animation) {
        List<String> out = new ArrayList<>();
        action.params().forEach((name, p) -> {
            if (p.required()) out.add(name);
        });
        if (animation != null && action.animations().containsKey(animation)) {
            action.animations().get(animation).params().forEach((name, p) -> {
                if (p.required()) out.add(name);
            });
        }
        return out;
    }

    private static CatalogKind kindOf(Map<String, Object> entry, OutputCatalog catalog) {
        Optional<OutputKind> kind = OutputKind.parse(entry.get("kind") instanceof String s ? s : null);
        return kind.map(catalog::kind).orElse(null);
    }

    private static List<Map.Entry<String, Long>> pinsOf(String name, Map<String, Object> entry, boolean hasPin) {
        List<Map.Entry<String, Long>> out = new ArrayList<>();
        if (hasPin && ShowNumbers.isNumber(entry.get("pin"))) {
            out.add(Map.entry(name, ((Number) entry.get("pin")).longValue()));
        }
        if ("motor".equals(entry.get("kind"))) {
            for (String key : List.of("pinR", "pinL", "sensorPin")) {
                if (ShowNumbers.isNumber(entry.get(key))) {
                    out.add(Map.entry(name + "." + key, ((Number) entry.get(key)).longValue()));
                }
            }
        }
        return out;
    }

    private static boolean sameNumber(Object value, int expected) {
        return ShowNumbers.isNumber(value) && ((Number) value).doubleValue() == expected;
    }

    private static List<String> declaredOf(ShowAssets assets, String kind) {
        List<String> list = "audio".equals(kind) ? assets.audio() : "video".equals(kind) ? assets.video() : null;
        return list == null ? List.of() : list;
    }
}
