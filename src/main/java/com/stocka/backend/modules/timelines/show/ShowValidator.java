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
import java.util.regex.Pattern;

import com.stocka.backend.modules.contract.BoardSpec;
import com.stocka.backend.modules.contract.CatalogAction;
import com.stocka.backend.modules.contract.CatalogKind;
import com.stocka.backend.modules.contract.CatalogParam;
import com.stocka.backend.modules.contract.FixtureProfile;
import com.stocka.backend.modules.contract.OutputCatalog;
import com.stocka.backend.modules.contract.PinMode;
import com.stocka.backend.modules.contract.OutputKind;
import com.stocka.backend.modules.contract.SensorProfile;
import com.stocka.backend.modules.timelines.show.ShowPackage.ShowAssets;
import com.stocka.backend.modules.timelines.show.ShowPackage.ShowEvent;

/**
 * Semantic rules of a {@code stocka-show} package, mirroring {@code rpi4-system/contract/validate.py}
 * and the editor's {@code utils/showValidate.ts}: every target exists, every action belongs to the
 * kind of its port, parameters are the catalog's, pins do not clash and segments fit their strip.
 * Runs on the assembled package, so the device will accept what passes here.
 */
public final class ShowValidator {

    private static final List<Integer> I2C_PINS = List.of(2, 3);
    private static final List<String> MOTOR_PIN_KEYS = List.of("pinForward", "pinReverse");
    private static final List<String> MOTOR_CHANNEL_KEYS = List.of("channelForward", "channelReverse");
    private static final List<String> MOTOR_LIMIT_KEYS = List.of("limitForward", "limitReverse");
    /** Action → {tope duro, final de carrera} del sentido. */
    private static final Map<String, String[]> MOTOR_DIRECTIONS = Map.of(
            "forward", new String[] {"maxTimeForward", "limitForward"},
            "reverse", new String[] {"maxTimeReverse", "limitReverse"});
    private static final int DMX_UNIVERSE = 512;
    private static final List<String> SENSOR_PIN_KEYS = List.of("pinTrig", "pinEcho");
    /** Keys only a {@code measured} sensor carries, and keys only a {@code digital} one carries. */
    private static final List<String> SENSOR_MEASURED_KEYS = List.of("model", "quantity", "bus", "address", "pinTrig", "pinEcho",
            "deviceId", "threshold", "activeWhen", "hysteresis", "pollMs");
    private static final List<String> SENSOR_DIGITAL_KEYS = List.of("expander", "channel", "activeLow", "pull");
    /** Wiring of a sensor profile → connection keys it accepts (the rest of SENSOR_WIRING_KEYS are extra). */
    private static final Map<String, List<String>> SENSOR_WIRING = Map.of(
            SensorProfile.WIRING_I2C, List.of("bus", "address"),
            SensorProfile.WIRING_ONEWIRE, List.of("pin", "deviceId"),
            SensorProfile.WIRING_TRIG_ECHO, SENSOR_PIN_KEYS);
    private static final List<String> SENSOR_WIRING_KEYS = List.of("pin", "bus", "address", "deviceId", "pinTrig", "pinEcho");
    private static final Pattern ONEWIRE_ID = Pattern.compile("^28-[0-9a-f]{12}$");

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
            boolean hasPin = ShowNumbers.isNumber(entry.get("pin"));
            if (spec.pin() == PinMode.REQUIRED && !hasPin) {
                issues.add(ShowIssue.error("E_CONFIG_INVALID", "El port «" + name + "» necessita «pin».", null, name));
            } else if (spec.pin() == PinMode.OPTIONAL && isMeasured(entry)) {
                // the wiring is checked by sensorProblems against the model
            } else if (spec.pin() == PinMode.OPTIONAL) {
                if (entry.containsKey("expander")) {
                    if (hasPin) {
                        issues.add(ShowIssue.error("E_CONFIG_INVALID", "El port «" + name + "» no pot tenir «pin» i «expander» alhora.", null, name));
                    }
                    if (!entry.containsKey("channel")) {
                        issues.add(ShowIssue.error("E_CONFIG_INVALID", "El port «" + name + "» necessita «channel» amb l'expansor.", null, name));
                    }
                    if (spec.kind() == OutputKind.RELAY && entry.containsKey("activeLow")) {
                        issues.add(ShowIssue.error("E_CONFIG_INVALID", "El port «" + name + "»: «activeLow» es declara a l'expansor, no al relé.", null, name));
                    }
                    if (spec.kind() == OutputKind.SENSOR && "down".equals(entry.get("pull"))) {
                        issues.add(ShowIssue.error("E_CONFIG_INVALID", "El sensor «" + name + "»: un canal d'expansor només admet pull «up» o «none».", null, name));
                    }
                } else if (!hasPin) {
                    issues.add(ShowIssue.error("E_CONFIG_INVALID", "El port «" + name + "» necessita «pin» o «expander» + «channel».", null, name));
                } else if (entry.containsKey("channel")) {
                    issues.add(ShowIssue.error("E_CONFIG_INVALID", "El port «" + name + "»: «channel» només té sentit amb «expander».", null, name));
                }
            } else if (spec.pin() == PinMode.NONE && entry.containsKey("pin")) {
                issues.add(ShowIssue.error("E_CONFIG_INVALID", "El port «" + name + "» (" + entry.get("kind") + ") no porta «pin».", null, name));
            }
            for (Map.Entry<String, Long> pinOf : pinsOf(name, entry, spec.pin().allowsPin())) {
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
                for (String why : motorOutputProblems(entry)) {
                    issues.add(ShowIssue.error("E_MOTOR_OUTPUTS", "El motor «" + name + "»: " + why + ".", null, name));
                }
            }
            if (spec.kind() == OutputKind.SENSOR) {
                for (String why : sensorProblems(entry, catalog)) {
                    issues.add(ShowIssue.error("E_SENSOR_CONFIG", "El sensor «" + name + "»: " + why + ".", null, name));
                }
            }
        }
        for (Map.Entry<Long, List<String>> used : usedPins.entrySet()) {
            if (used.getValue().size() > 1) {
                issues.add(ShowIssue.error("E_PIN_DUPLICATE", "El pin " + used.getKey() + " està usat per més d'un port: "
                        + String.join(", ", used.getValue()) + "."));
            }
        }

        // --- I2C devices (expanders and measured sensors): unique addresses, GPIO 2/3 reserved ------
        Map<String, Map<String, Object>> expanders = new LinkedHashMap<>();
        hardware.forEach((n, e) -> {
            if ("gpio_expander".equals(e.get("kind"))) expanders.put(n, e);
        });
        List<Object[]> i2cDevices = new ArrayList<>(); // {name, bus, address or null}
        for (Map.Entry<String, Map<String, Object>> x : expanders.entrySet()) {
            Object address = x.getValue().get("address");
            i2cDevices.add(new Object[] {x.getKey(), busOf(x.getValue()), ShowNumbers.isNumber(address) ? ((Number) address).longValue() : null});
        }
        for (Map.Entry<String, Map<String, Object>> h : hardware.entrySet()) {
            Map<String, Object> entry = h.getValue();
            SensorProfile profile = isMeasured(entry) ? catalog.sensorProfile(entry.get("model") instanceof String m ? m : null).orElse(null) : null;
            if (profile != null && SensorProfile.WIRING_I2C.equals(profile.wiring())
                    && (!entry.containsKey("bus") || ShowNumbers.isNumber(entry.get("bus")))) {
                Integer configured = ShowNumbers.isInteger(entry.get("address")) ? ((Number) entry.get("address")).intValue() : null;
                i2cDevices.add(new Object[] {h.getKey(), busOf(entry), profile.effectiveAddress(configured).map(Integer::longValue).orElse(null)});
            }
        }
        if (!i2cDevices.isEmpty()) {
            for (Integer pin : I2C_PINS) {
                for (String label : usedPins.getOrDefault(pin.longValue(), List.of())) {
                    issues.add(ShowIssue.error("E_PIN_RESERVED", "El pin " + pin + " de «" + label
                            + "» és I2C i queda reservat quan hi ha un expansor o un sensor I2C.", null, label));
                }
            }
            Map<String, List<String>> addresses = new LinkedHashMap<>();
            for (Object[] device : i2cDevices) {
                if (device[2] != null) {
                    addresses.computeIfAbsent(device[1] + ":" + device[2], k -> new ArrayList<>()).add((String) device[0]);
                }
            }
            for (Map.Entry<String, List<String>> a : addresses.entrySet()) {
                if (a.getValue().size() > 1) {
                    String[] parts = a.getKey().split(":");
                    issues.add(ShowIssue.error("E_I2C_ADDRESS_DUPLICATE", "L'adreça I2C 0x" + Long.toHexString(Long.parseLong(parts[1]))
                            + " del bus " + parts[0] + " la fan servir més d'un dispositiu: " + String.join(", ", a.getValue()) + "."));
                }
            }
        }
        Map<String, List<String>> channels = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> h : hardware.entrySet()) {
            Map<String, Object> entry = h.getValue();
            Object kind = entry.get("kind");
            if (!("relay".equals(kind) || "sensor".equals(kind) || "motor".equals(kind)) || !entry.containsKey("expander")) continue;
            String expander = String.valueOf(entry.get("expander"));
            if (!expanders.containsKey(expander)) {
                issues.add(ShowIssue.error("E_EXPANDER_MISSING", "El port «" + h.getKey() + "» referencia l'expansor «" + expander
                        + "», que no existeix com a gpio_expander.", null, h.getKey()));
                continue;
            }
            List<String> keys = "motor".equals(kind) ? MOTOR_CHANNEL_KEYS : List.of("channel");
            for (String key : keys) {
                if (ShowNumbers.isInteger(entry.get(key))) {
                    String label = "channel".equals(key) ? h.getKey() : h.getKey() + "." + key;
                    channels.computeIfAbsent(expander + ":" + ((Number) entry.get(key)).longValue(), k -> new ArrayList<>()).add(label);
                }
            }
        }
        for (Map.Entry<String, List<String>> c : channels.entrySet()) {
            if (c.getValue().size() > 1) {
                int sep = c.getKey().lastIndexOf(':');
                issues.add(ShowIssue.error("E_EXPANDER_CHANNEL_DUPLICATE", "El canal " + c.getKey().substring(sep + 1) + " de «"
                        + c.getKey().substring(0, sep) + "» l'usen més d'un port: " + String.join(", ", c.getValue()) + "."));
            }
        }

        // --- motors: limit switches; sensors nobody uses ----------------------------------------------
        Set<String> referencedSensors = new HashSet<>();
        for (Map.Entry<String, Map<String, Object>> h : hardware.entrySet()) {
            Map<String, Object> entry = h.getValue();
            if (!"motor".equals(entry.get("kind"))) continue;
            for (String key : MOTOR_LIMIT_KEYS) {
                if (!entry.containsKey(key)) continue;
                Object sensor = entry.get(key);
                Map<String, Object> target = sensor instanceof String n ? hardware.get(n) : null;
                if (target == null || !"sensor".equals(target.get("kind"))) {
                    issues.add(ShowIssue.error("E_SENSOR_MISSING", "El motor «" + h.getKey() + "»: «" + key + "» referencia «" + sensor
                            + "», que no existeix com a sensor.", null, h.getKey()));
                } else {
                    referencedSensors.add((String) sensor);
                }
            }
        }
        for (Map.Entry<String, Map<String, Object>> h : hardware.entrySet()) {
            Map<String, Object> entry = h.getValue();
            if ("sensor".equals(entry.get("kind")) && "none".equals(entry.getOrDefault("onActive", "none"))
                    && !referencedSensors.contains(h.getKey())) {
                issues.add(ShowIssue.warning("W_SENSOR_UNUSED", "El sensor «" + h.getKey()
                        + "» no fa res: cap motor el fa servir i no té onActive.", null, h.getKey()));
            }
        }

        // --- moving heads: inside the universe, no overlaps ---------------------------------------
        List<Object[]> dmxRanges = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> h : hardware.entrySet()) {
            Map<String, Object> entry = h.getValue();
            if (!"moving_head".equals(entry.get("kind"))) continue;
            Optional<FixtureProfile> profile = catalog.movingHeadProfile(entry.get("profile") instanceof String s ? s : null);
            if (profile.isEmpty() || !ShowNumbers.isInteger(entry.get("address"))) continue;
            int address = ((Number) entry.get("address")).intValue();
            int last = profile.get().lastChannel(address);
            if (last > DMX_UNIVERSE) {
                issues.add(ShowIssue.error("E_DMX_ADDRESS_RANGE", "El cap «" + h.getKey() + "» a l'adreça " + address + " necessita "
                        + profile.get().channels() + " canals i surt de l'univers (fins a " + last + ").", null, h.getKey()));
                continue;
            }
            for (Object[] other : dmxRanges) {
                int a = (int) other[1];
                int b = (int) other[2];
                if (address <= b && a <= last) {
                    issues.add(ShowIssue.error("E_DMX_ADDRESS_OVERLAP", "Els caps «" + other[0] + "» (" + a + "–" + b + ") i «"
                            + h.getKey() + "» (" + address + "–" + last + ") solapen canals DMX.", null, h.getKey()));
                }
            }
            dmxRanges.add(new Object[] {h.getKey(), address, last});
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
        ShowAssets declared = assets == null ? new ShowAssets(List.of()) : assets;
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
            if (spec.kind() == OutputKind.MOTOR && MOTOR_DIRECTIONS.containsKey(ev.action()) && ShowNumbers.isNumber(params.get("time"))) {
                String maxKey = MOTOR_DIRECTIONS.get(ev.action())[0];
                String limitKey = MOTOR_DIRECTIONS.get(ev.action())[1];
                Object limitTime = entry.get(maxKey);
                double time = ((Number) params.get("time")).doubleValue();
                if (ShowNumbers.isNumber(limitTime) && time > ((Number) limitTime).doubleValue()) {
                    String t = ShowNumbers.toJsString((Number) params.get("time"));
                    String max = ShowNumbers.toJsString((Number) limitTime);
                    Object limit = entry.get(limitKey);
                    if (limit != null && !"".equals(limit)) {
                        issues.add(ShowIssue.warning("W_MOTOR_TIME_CAPPED", where + ": el clip dura " + t + " s però «" + ev.target()
                                + "» mai gira més de " + max + " s en aquest sentit; pararà abans pel final de carrera o pel tall.",
                                null, ev.target()));
                    } else {
                        issues.add(ShowIssue.error("E_MOTOR_TIME", where + ": time=" + t + " supera el " + maxKey + " (" + max
                                + ") del motor «" + ev.target() + "».", null, ev.target()));
                    }
                }
            }
            if (spec.kind() == OutputKind.MOVING_HEAD && "set".equals(ev.action())
                    && params.keySet().stream().allMatch("time"::equals)) {
                issues.add(ShowIssue.warning("W_EMPTY_CUE", where + ": el cue de «" + ev.target() + "» no canvia res (només time).",
                        null, ev.target()));
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
            if (spec.kind() == OutputKind.AUDIO) {
                singletons.computeIfAbsent(spec.kind().catalogId(), k -> new ArrayList<>()).add(h.getKey());
            }
        }
        for (Map.Entry<String, List<String>> s : singletons.entrySet()) {
            if (s.getValue().size() > 1) {
                issues.add(ShowIssue.warning("W_LEGACY_SINGLE", "El reproductor actual només admet un port de tipus "
                        + s.getKey() + "; n'hi ha " + s.getValue().size() + ": " + String.join(", ", s.getValue()) + "."));
            }
        }
        for (String assetKind : List.of("audio")) {
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

    /**
     * Problems of a motor's outputs (pins or expander), in Catalan; empty when coherent. Port of
     * {@code motor_output_problems} in {@code contract/validate.py}.
     *
     * @param entry hardware entry of a motor
     * @return the problems
     */
    static List<String> motorOutputProblems(Map<String, Object> entry) {
        long pins = MOTOR_PIN_KEYS.stream().filter(entry::containsKey).count();
        long channels = MOTOR_CHANNEL_KEYS.stream().filter(entry::containsKey).count();
        List<String> problems = new ArrayList<>();
        if (entry.containsKey("expander")) {
            if (pins > 0) problems.add("no pot tenir pins i expansor alhora");
            if (channels != 2) {
                problems.add("amb expansor necessita channelForward i channelReverse");
            } else if (sameValue(entry.get("channelForward"), entry.get("channelReverse"))) {
                problems.add("channelForward i channelReverse han de ser diferents");
            }
            if (entry.containsKey("activeLow")) problems.add("«activeLow» es declara a l'expansor");
        } else {
            if (channels > 0) problems.add("els canals només tenen sentit amb «expander»");
            if (pins != 2) problems.add("necessita pinForward i pinReverse, o expander amb els dos canals");
        }
        return problems;
    }

    /** @return whether the entry is a sensor that reads a value ({@code source: measured}) */
    static boolean isMeasured(Map<String, Object> entry) {
        return "sensor".equals(entry.get("kind")) && "measured".equals(entry.getOrDefault("source", "digital"));
    }

    private static long busOf(Map<String, Object> entry) {
        return ShowNumbers.isNumber(entry.get("bus")) ? ((Number) entry.get("bus")).longValue() : 1;
    }

    /**
     * Problems of a sensor according to its {@code source} (and, when measured, its model), in Catalan;
     * empty when coherent. Port of {@code sensor_problems} in {@code contract/validate.py}.
     *
     * @param entry   hardware entry of a sensor
     * @param catalog output catalog (sensor profiles)
     * @return the problems
     */
    static List<String> sensorProblems(Map<String, Object> entry, OutputCatalog catalog) {
        List<String> problems = new ArrayList<>();
        if (!isMeasured(entry)) {
            List<String> extra = SENSOR_MEASURED_KEYS.stream().filter(entry::containsKey).map(k -> "«" + k + "»").toList();
            if (!extra.isEmpty()) {
                problems.add(String.join(", ", extra) + " només té sentit amb source measured");
            }
            return problems;
        }
        for (String key : SENSOR_DIGITAL_KEYS) {
            if (entry.containsKey(key)) problems.add("«" + key + "» és d'un sensor digital");
        }
        Object model = entry.get("model");
        SensorProfile profile = catalog.sensorProfile(model instanceof String m ? m : null).orElse(null);
        if (profile == null) {
            problems.add("necessita un «model» de profiles.sensor");
            return problems;
        }
        List<String> allowed = SENSOR_WIRING.getOrDefault(profile.wiring(), List.of());
        for (String key : SENSOR_WIRING_KEYS) {
            if (entry.containsKey(key) && !allowed.contains(key)) {
                problems.add("«" + key + "» no correspon al cablejat " + profile.wiring() + " del model " + model);
            }
        }
        switch (profile.wiring()) {
            case SensorProfile.WIRING_ONEWIRE -> {
                if (!entry.containsKey("pin")) problems.add("necessita «pin» (la línia DATA del 1-Wire)");
                if (entry.containsKey("deviceId") && !(entry.get("deviceId") instanceof String id && ONEWIRE_ID.matcher(id).matches())) {
                    problems.add("«deviceId» ha de ser 28- seguit de 12 dígits hexadecimals");
                }
            }
            case SensorProfile.WIRING_TRIG_ECHO -> {
                if (!entry.containsKey("pinTrig") || !entry.containsKey("pinEcho")) {
                    problems.add("necessita «pinTrig» i «pinEcho»");
                } else if (sameValue(entry.get("pinTrig"), entry.get("pinEcho"))) {
                    problems.add("«pinTrig» i «pinEcho» han de ser diferents");
                }
            }
            case SensorProfile.WIRING_I2C -> {
                if (entry.containsKey("address") && !profile.addresses().isEmpty()
                        && !(ShowNumbers.isInteger(entry.get("address")) && profile.addresses().contains(((Number) entry.get("address")).intValue()))) {
                    problems.add("el model " + model + " només admet l'adreça I2C "
                            + String.join(" o ", profile.addresses().stream().map(a -> "0x" + Integer.toHexString(a)).toList()));
                }
            }
            default -> {
            }
        }
        Object requested = entry.get("quantity");
        String quantity = profile.resolveQuantity(requested instanceof String q ? q : null).orElse(null);
        String measures = String.join(", ", profile.quantities().keySet());
        if (quantity == null || (requested != null && !(requested instanceof String))) {
            quantity = null;
            problems.add(entry.containsKey("quantity")
                    ? "el model " + model + " no mesura " + requested + " (mesura " + measures + ")"
                    : "necessita «quantity»: el model " + model + " mesura " + measures);
        }
        Object threshold = entry.get("threshold");
        if (!ShowNumbers.isNumber(threshold)) {
            problems.add("necessita «threshold»");
        } else if (quantity != null) {
            SensorProfile.Quantity range = profile.quantities().get(quantity);
            BigDecimal value = new BigDecimal(ShowNumbers.normalize((Number) threshold).toString());
            if ((range.min() != null && value.compareTo(range.min()) < 0) || (range.max() != null && value.compareTo(range.max()) > 0)) {
                problems.add("«threshold» ha d'estar entre " + ShowNumbers.toJsString(range.min()) + " i "
                        + ShowNumbers.toJsString(range.max()) + " " + range.unit());
            }
        }
        if (!"above".equals(entry.get("activeWhen")) && !"below".equals(entry.get("activeWhen"))) {
            problems.add("necessita «activeWhen» (above o below)");
        }
        return problems;
    }

    private static boolean sameValue(Object a, Object b) {
        return ShowNumbers.isNumber(a) && ShowNumbers.isNumber(b) && ((Number) a).doubleValue() == ((Number) b).doubleValue();
    }

    private static List<Map.Entry<String, Long>> pinsOf(String name, Map<String, Object> entry, boolean hasPin) {
        List<Map.Entry<String, Long>> out = new ArrayList<>();
        if (hasPin && ShowNumbers.isNumber(entry.get("pin"))) {
            out.add(Map.entry(name, ((Number) entry.get("pin")).longValue()));
        }
        if ("motor".equals(entry.get("kind"))) {
            for (String key : MOTOR_PIN_KEYS) {
                if (ShowNumbers.isNumber(entry.get(key))) {
                    out.add(Map.entry(name + "." + key, ((Number) entry.get(key)).longValue()));
                }
            }
        }
        if ("sensor".equals(entry.get("kind"))) {
            for (String key : SENSOR_PIN_KEYS) {
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
        List<String> list = "audio".equals(kind) ? assets.audio() : null;
        return list == null ? List.of() : list;
    }
}
