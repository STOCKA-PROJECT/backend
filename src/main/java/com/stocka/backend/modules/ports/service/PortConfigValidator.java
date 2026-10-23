package com.stocka.backend.modules.ports.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.stocka.backend.modules.common.error.ApiException;
import com.stocka.backend.modules.common.error.ErrorCodes;
import com.stocka.backend.modules.contract.CatalogParam;
import com.stocka.backend.modules.contract.CatalogType;
import com.stocka.backend.modules.contract.OutputCatalog;
import com.stocka.backend.modules.contract.OutputKind;
import com.stocka.backend.modules.contract.SensorProfile;

/**
 * Validates and normalizes a port's kind-specific config against the output catalog.
 *
 * <p>Rules: no unknown keys, every required key present, JSON types matching the catalog type
 * ({@code int} must be an integral number, {@code number} any number, {@code bool} a boolean,
 * {@code string} a string), bounds respected; relays and sensors carry {@code channel} only
 * together with {@code expander}; a relay or motor on an expander takes the board's polarity (no
 * {@code activeLow}), a sensor on an expander cannot pull down; a motor takes both native outputs or
 * the expander with both channels, all distinct. A sensor is {@code digital} (the rules above) or
 * {@code measured}: then it names a {@code model} of {@code profiles.sensor}, takes only the wiring keys
 * of that model, a {@code threshold} inside the range of its quantity and an {@code activeWhen}
 * ({@code ports.sensor_config_invalid} with {@code params.reason}). {@code pin} inside the config is
 * ignored because it lives in {@code Port.pin}; whether a relay needs a pin or an expander is decided
 * by {@link PortService}, which sees both. Cross-port rules (strip existence, segment range, pin
 * collisions, expander channels, DMX addresses) are checked by {@link PortService} too.
 */
@Component
public class PortConfigValidator {
    /** Motor config keys holding native GPIO outputs (forward = IN1, reverse = IN2 of the H-bridge). */
    public static final List<String> MOTOR_PIN_KEYS = List.of("pinForward", "pinReverse");
    /** Motor config keys holding expander channels. */
    public static final List<String> MOTOR_CHANNEL_KEYS = List.of("channelForward", "channelReverse");
    /** Motor config keys naming the limit-switch sensors of each direction. */
    public static final List<String> MOTOR_LIMIT_KEYS = List.of("limitForward", "limitReverse");
    /** Sensor config key: internal resistor. */
    public static final String PULL_KEY = "pull";
    /** Relay config key naming the {@code gpio_expander} port the relay hangs from. */
    public static final String EXPANDER_KEY = "expander";
    /** Relay config key: channel (0–15) on the expander. */
    public static final String CHANNEL_KEY = "channel";
    /** Relay and expander config key: the board switches on at low level. */
    public static final String ACTIVE_LOW_KEY = "activeLow";
    /** Sensor config key: {@code digital} (default) or {@code measured}. */
    public static final String SOURCE_KEY = "source";
    /** Value of {@link #SOURCE_KEY} for sensors that read a value through a model of {@code profiles.sensor}. */
    public static final String SOURCE_MEASURED = "measured";
    /** Measured-sensor config key naming its model. */
    public static final String MODEL_KEY = "model";
    /** Measured-sensor config key: I2C bus. */
    public static final String BUS_KEY = "bus";
    /** Measured-sensor (and expander) config key: I2C address. */
    public static final String ADDRESS_KEY = "address";
    /** Measured-sensor config keys holding the two GPIOs of a {@code trig_echo} model. */
    public static final List<String> SENSOR_PIN_KEYS = List.of("pinTrig", "pinEcho");
    /** Config keys only a measured sensor may carry. */
    public static final List<String> SENSOR_MEASURED_KEYS = List.of(MODEL_KEY, "quantity", BUS_KEY, ADDRESS_KEY,
            "pinTrig", "pinEcho", "deviceId", "threshold", "activeWhen", "hysteresis", "pollMs");
    /** Config keys only a digital sensor may carry. */
    public static final List<String> SENSOR_DIGITAL_KEYS = List.of(EXPANDER_KEY, CHANNEL_KEY, ACTIVE_LOW_KEY, PULL_KEY);
    /** Connection keys of each wiring; a measured sensor may only carry those of its model ({@code pin} lives in the port). */
    private static final Map<String, List<String>> WIRING_KEYS = Map.of(
            SensorProfile.WIRING_I2C, List.of(BUS_KEY, ADDRESS_KEY),
            SensorProfile.WIRING_ONEWIRE, List.of("deviceId"),
            SensorProfile.WIRING_TRIG_ECHO, SENSOR_PIN_KEYS);
    private static final List<String> ALL_WIRING_KEYS = List.of(BUS_KEY, ADDRESS_KEY, "deviceId", "pinTrig", "pinEcho");
    private static final Pattern ONEWIRE_ID = Pattern.compile("^28-[0-9a-f]{12}$");

    private final OutputCatalog catalog;

    public PortConfigValidator(OutputCatalog catalog) {
        this.catalog = catalog;
    }

    /**
     * Checks {@code raw} against the config schema of {@code kind} and returns a normalized copy
     * ordered as the catalog declares the keys.
     *
     * @param kind output kind of the port
     * @param raw  inbound config; {@code null} means empty
     * @return the normalized config
     * @throws ApiException 400 with a {@code ports.config_*} code on any violation
     */
    public Map<String, Object> normalize(OutputKind kind, Map<String, Object> raw) {
        Map<String, CatalogParam> schema = catalog.configSchema(kind);
        Map<String, Object> input = raw == null ? Map.of() : raw;

        for (String key : input.keySet()) {
            if (!"pin".equals(key) && !schema.containsKey(key)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_CONFIG_UNKNOWN_KEY, params("key", key));
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        for (CatalogParam field : schema.values()) {
            Object value = input.get(field.name());
            if (value == null) {
                if (field.required()) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_CONFIG_MISSING_KEY,
                            params("key", field.name()));
                }
                continue;
            }
            out.put(field.name(), coerce(field, value));
        }

        if (kind == OutputKind.SENSOR) {
            validateSensorSource(out);
        }
        if (kind == OutputKind.RELAY || kind == OutputKind.SENSOR) {
            boolean onExpander = out.containsKey(EXPANDER_KEY);
            if (!onExpander && out.containsKey(CHANNEL_KEY)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_CHANNEL_WITHOUT_EXPANDER);
            }
            if (onExpander && !out.containsKey(CHANNEL_KEY)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_CHANNEL_REQUIRED);
            }
            // A relay on an expander takes the polarity of the expander's board; a sensor keeps its own.
            if (kind == OutputKind.RELAY && onExpander && out.containsKey(ACTIVE_LOW_KEY)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_ACTIVE_LOW_ON_EXPANDER);
            }
            if (kind == OutputKind.SENSOR && onExpander && "down".equals(out.get(PULL_KEY))) {
                throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_SENSOR_PULL_ON_EXPANDER);
            }
        }
        if (kind == OutputKind.MOTOR) {
            validateMotorOutputs(out);
        }
        return out;
    }

    /**
     * Whether a sensor config reads a value through a model ({@code source: measured}).
     *
     * @param kind   output kind
     * @param config normalized config
     * @return {@code true} for measured sensors
     */
    public static boolean isMeasured(OutputKind kind, Map<String, Object> config) {
        return kind == OutputKind.SENSOR && config != null && SOURCE_MEASURED.equals(config.get(SOURCE_KEY));
    }

    /**
     * Model of a measured sensor.
     *
     * @param kind   output kind
     * @param config normalized config
     * @return the profile, or empty when the port is not a measured sensor of a known model
     */
    public Optional<SensorProfile> sensorProfile(OutputKind kind, Map<String, Object> config) {
        if (!isMeasured(kind, config)) {
            return Optional.empty();
        }
        return catalog.sensorProfile(config.get(MODEL_KEY) instanceof String s ? s : null);
    }

    /**
     * The I2C slot ({@code bus:address}) a port occupies: expanders and measured sensors of an I2C model.
     *
     * @param kind   output kind
     * @param config normalized config
     * @return {@code [bus, address]}, or empty when the port is not on the I2C bus
     */
    public Optional<int[]> i2cSlot(OutputKind kind, Map<String, Object> config) {
        if (config == null) {
            return Optional.empty();
        }
        int bus = config.get(BUS_KEY) instanceof Number n ? n.intValue() : 1;
        if (kind == OutputKind.GPIO_EXPANDER) {
            return config.get(ADDRESS_KEY) instanceof Number a ? Optional.of(new int[] {bus, a.intValue()}) : Optional.empty();
        }
        Integer configured = config.get(ADDRESS_KEY) instanceof Number a ? a.intValue() : null;
        return sensorProfile(kind, config)
                .flatMap(profile -> profile.effectiveAddress(configured))
                .map(address -> new int[] {bus, address});
    }

    /**
     * Whether a port of {@code kind} with {@code config} carries no pin of its own in {@code Port.pin}:
     * relays and sensors on an expander, and measured sensors whose model is not wired to one GPIO.
     *
     * @param kind   output kind
     * @param config normalized config
     * @return {@code true} when {@code Port.pin} must stay empty
     */
    public boolean takesNoPortPin(OutputKind kind, Map<String, Object> config) {
        if (config != null && config.containsKey(EXPANDER_KEY)) {
            return true;
        }
        return sensorProfile(kind, config).map(p -> !SensorProfile.WIRING_ONEWIRE.equals(p.wiring())).orElse(false);
    }

    /**
     * Pins declared inside a config: the two native motor outputs and the two GPIOs of a
     * {@code trig_echo} sensor.
     *
     * @param kind   output kind
     * @param config normalized config
     * @return the pins, in declaration order; empty for kinds without config pins
     */
    public List<Integer> configPins(OutputKind kind, Map<String, Object> config) {
        List<Integer> pins = new ArrayList<>();
        if (kind == OutputKind.SENSOR && config != null) {
            for (String key : SENSOR_PIN_KEYS) {
                if (config.get(key) instanceof Number n) {
                    pins.add(n.intValue());
                }
            }
        }
        if (kind == OutputKind.MOTOR && config != null) {
            for (String key : MOTOR_PIN_KEYS) {
                Object v = config.get(key);
                if (v instanceof Number n) {
                    pins.add(n.intValue());
                }
            }
        }
        return pins;
    }

    /**
     * A motor takes both native outputs or the expander with both channels, never a mix; the two
     * outputs must differ, and the polarity of an expander's outputs belongs to the expander.
     */
    private static void validateMotorOutputs(Map<String, Object> out) {
        long pins = MOTOR_PIN_KEYS.stream().filter(out::containsKey).count();
        long channels = MOTOR_CHANNEL_KEYS.stream().filter(out::containsKey).count();
        if (out.containsKey(EXPANDER_KEY)) {
            if (pins > 0) {
                throw motorOutputs("pins_and_expander");
            }
            if (channels != MOTOR_CHANNEL_KEYS.size()) {
                throw motorOutputs("missing_channel");
            }
            if (out.containsKey(ACTIVE_LOW_KEY)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_ACTIVE_LOW_ON_EXPANDER);
            }
            if (out.get("channelForward").equals(out.get("channelReverse"))) {
                throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_MOTOR_PINS_NOT_DISTINCT);
            }
            return;
        }
        if (channels > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_CHANNEL_WITHOUT_EXPANDER);
        }
        if (pins != MOTOR_PIN_KEYS.size()) {
            throw motorOutputs("missing_pin");
        }
        Set<Integer> distinct = new HashSet<>();
        for (String key : MOTOR_PIN_KEYS) {
            if (!distinct.add(((Number) out.get(key)).intValue())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_MOTOR_PINS_NOT_DISTINCT);
            }
        }
    }

    /**
     * A digital sensor carries none of the measured keys; a measured one none of the digital keys, a
     * known model, only the wiring keys of that model, a threshold inside the range of the watched
     * quantity and an {@code activeWhen}. Whether a 1-Wire model has its pin is checked by
     * {@link PortService}, which sees {@code Port.pin}.
     */
    private void validateSensorSource(Map<String, Object> out) {
        if (!SOURCE_MEASURED.equals(out.get(SOURCE_KEY))) {
            for (String key : SENSOR_MEASURED_KEYS) {
                if (out.containsKey(key)) {
                    throw sensorConfig("measured_key", params("key", key));
                }
            }
            return;
        }
        for (String key : SENSOR_DIGITAL_KEYS) {
            if (out.containsKey(key)) {
                throw sensorConfig("digital_key", params("key", key));
            }
        }
        SensorProfile profile = catalog.sensorProfile(out.get(MODEL_KEY) instanceof String s ? s : null)
                .orElseThrow(() -> sensorConfig("model_required", new LinkedHashMap<>()));
        List<String> allowed = WIRING_KEYS.getOrDefault(profile.wiring(), List.of());
        for (String key : ALL_WIRING_KEYS) {
            if (out.containsKey(key) && !allowed.contains(key)) {
                Map<String, Object> p = params("key", key);
                p.put("wiring", profile.wiring());
                throw sensorConfig("wiring_key", p);
            }
        }
        if (SensorProfile.WIRING_TRIG_ECHO.equals(profile.wiring())) {
            if (!out.containsKey("pinTrig") || !out.containsKey("pinEcho")) {
                throw sensorConfig("trig_echo_pins_required", new LinkedHashMap<>());
            }
            if (out.get("pinTrig").equals(out.get("pinEcho"))) {
                throw sensorConfig("trig_echo_pins_equal", new LinkedHashMap<>());
            }
        }
        if (out.get("deviceId") instanceof String id && !ONEWIRE_ID.matcher(id).matches()) {
            throw sensorConfig("device_id_invalid", params("deviceId", id));
        }
        if (out.get(ADDRESS_KEY) instanceof Number a && !profile.addresses().isEmpty() && !profile.addresses().contains(a.intValue())) {
            Map<String, Object> p = params("address", a.intValue());
            p.put("addresses", String.join(", ", profile.addresses().stream().map(x -> String.format("0x%x", x)).toList()));
            throw sensorConfig("address_not_in_profile", p);
        }
        String requested = out.get("quantity") instanceof String q ? q : null;
        String quantity = profile.resolveQuantity(requested).orElseThrow(() -> {
            Map<String, Object> p = params("quantities", String.join(", ", profile.quantities().keySet()));
            if (requested != null) {
                p.put("quantity", requested);
            }
            return sensorConfig(requested == null ? "quantity_required" : "quantity_not_measured", p);
        });
        if (!(out.get("threshold") instanceof Number threshold)) {
            throw sensorConfig("threshold_required", new LinkedHashMap<>());
        }
        SensorProfile.Quantity range = profile.quantities().get(quantity);
        BigDecimal value = new BigDecimal(threshold.toString());
        if ((range.min() != null && value.compareTo(range.min()) < 0) || (range.max() != null && value.compareTo(range.max()) > 0)) {
            Map<String, Object> p = params("min", range.min() == null ? "-" : range.min().stripTrailingZeros().toPlainString());
            p.put("max", range.max() == null ? "-" : range.max().stripTrailingZeros().toPlainString());
            p.put("unit", range.unit());
            throw sensorConfig("threshold_out_of_range", p);
        }
        if (!out.containsKey("activeWhen")) {
            throw sensorConfig("active_when_required", new LinkedHashMap<>());
        }
    }

    private static ApiException sensorConfig(String reason, Map<String, Object> extra) {
        Map<String, Object> p = params("reason", reason);
        p.putAll(extra);
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_SENSOR_CONFIG_INVALID, p);
    }

    private static ApiException motorOutputs(String reason) {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_MOTOR_OUTPUTS_INVALID, params("reason", reason));
    }

    /**
     * Expander channels a port occupies, by config key ({@code channel} for relays and sensors, the
     * two motor channels for motors).
     *
     * @param kind   output kind
     * @param config normalized config
     * @return the channels; empty when the port is not on an expander
     */
    public List<Integer> expanderChannels(OutputKind kind, Map<String, Object> config) {
        List<Integer> out = new ArrayList<>();
        if (config == null || !config.containsKey(EXPANDER_KEY)) {
            return out;
        }
        List<String> keys = kind == OutputKind.MOTOR ? MOTOR_CHANNEL_KEYS
                : (kind == OutputKind.RELAY || kind == OutputKind.SENSOR) ? List.of(CHANNEL_KEY) : List.of();
        for (String key : keys) {
            if (config.get(key) instanceof Number n) {
                out.add(n.intValue());
            }
        }
        return out;
    }

    private static Object coerce(CatalogParam field, Object value) {
        String key = field.name();
        CatalogType type = field.type();
        switch (type) {
            case INT -> {
                Number n = asNumber(value, key, "int");
                if (n instanceof Double || n instanceof Float || n instanceof BigDecimal) {
                    BigDecimal d = new BigDecimal(n.toString());
                    if (d.stripTrailingZeros().scale() > 0) {
                        throw typeMismatch(key, "int");
                    }
                }
                checkBounds(field, new BigDecimal(n.toString()));
                return n.intValue();
            }
            case NUMBER -> {
                Number n = asNumber(value, key, "number");
                checkBounds(field, new BigDecimal(n.toString()));
                return n;
            }
            case BOOL -> {
                if (!(value instanceof Boolean)) {
                    throw typeMismatch(key, "bool");
                }
                return value;
            }
            case STRING -> {
                if (!(value instanceof String s) || s.isBlank()) {
                    throw typeMismatch(key, "string");
                }
                return s.trim();
            }
            case RGB_LIST -> {
                if (!(value instanceof List<?> list)) {
                    throw typeMismatch(key, "rgb_list");
                }
                return value;
            }
            case ENUM -> {
                if (!(value instanceof String s) || !field.values().contains(s)) {
                    throw typeMismatch(key, "enum");
                }
                return value;
            }
            case RGB -> {
                if (!(value instanceof List<?> list) || list.size() != 3
                        || !list.stream().allMatch(c -> c instanceof Number n && n.intValue() >= 0 && n.intValue() <= 255)) {
                    throw typeMismatch(key, "rgb");
                }
                return list.stream().map(c -> ((Number) c).intValue()).toList();
            }
        }
        return value;
    }

    private static Number asNumber(Object value, String key, String type) {
        if (value instanceof Boolean || !(value instanceof Number n)) {
            throw typeMismatch(key, type);
        }
        return n;
    }

    private static void checkBounds(CatalogParam field, BigDecimal value) {
        if ((field.min() != null && value.compareTo(field.min()) < 0)
                || (field.max() != null && value.compareTo(field.max()) > 0)) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("key", field.name());
            p.put("min", field.min() == null ? "-" : field.min().stripTrailingZeros().toPlainString());
            p.put("max", field.max() == null ? "-" : field.max().stripTrailingZeros().toPlainString());
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_CONFIG_OUT_OF_RANGE, p);
        }
    }

    private static ApiException typeMismatch(String key, String type) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("key", key);
        p.put("type", type);
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_CONFIG_TYPE_MISMATCH, p);
    }

    private static Map<String, Object> params(String key, Object value) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put(key, value);
        return p;
    }
}
