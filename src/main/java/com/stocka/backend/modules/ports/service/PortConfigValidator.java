package com.stocka.backend.modules.ports.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.stocka.backend.modules.common.error.ApiException;
import com.stocka.backend.modules.common.error.ErrorCodes;
import com.stocka.backend.modules.contract.CatalogParam;
import com.stocka.backend.modules.contract.CatalogType;
import com.stocka.backend.modules.contract.OutputCatalog;
import com.stocka.backend.modules.contract.OutputKind;

/**
 * Validates and normalizes a port's kind-specific config against the output catalog.
 *
 * <p>Rules: no unknown keys, every required key present, JSON types matching the catalog type
 * ({@code int} must be an integral number, {@code number} any number, {@code bool} a boolean,
 * {@code string} a string), bounds respected, and for motors the three pins distinct. {@code pin}
 * inside the config is ignored because it lives in {@code Port.pin}. Cross-port rules (strip
 * existence, segment range, pin collisions) are checked by {@link PortService} because they need the
 * repository.
 */
@Component
public class PortConfigValidator {
    private static final List<String> MOTOR_PIN_KEYS = List.of("pinR", "pinL", "sensorPin");

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

        if (kind == OutputKind.MOTOR) {
            Set<Integer> distinct = new HashSet<>();
            for (String key : MOTOR_PIN_KEYS) {
                if (!distinct.add(((Number) out.get(key)).intValue())) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_MOTOR_PINS_NOT_DISTINCT);
                }
            }
        }
        return out;
    }

    /**
     * Pins declared inside a config (today only the three motor pins).
     *
     * @param kind   output kind
     * @param config normalized config
     * @return the pins, in declaration order; empty for kinds without config pins
     */
    public List<Integer> configPins(OutputKind kind, Map<String, Object> config) {
        List<Integer> pins = new ArrayList<>();
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
