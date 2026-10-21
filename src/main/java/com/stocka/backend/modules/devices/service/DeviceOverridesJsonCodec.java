package com.stocka.backend.modules.devices.service;

import java.util.ArrayList;
import java.util.List;

import com.stocka.backend.modules.devices.dto.DeviceAlertDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Round-trips the list of overridden (cut) port names and the alerts a player reports between its JSON form (the
 * {@code last_overrides} TEXT column) and a {@code List<String>}. Keeps Jackson 3 usage in one
 * place, mirroring {@code PortConfigJsonCodec}.
 */
@Component
public class DeviceOverridesJsonCodec {
    private static final Logger log = LoggerFactory.getLogger(DeviceOverridesJsonCodec.class);
    private static final TypeReference<ArrayList<String>> LIST_TYPE = new TypeReference<>() {};
    private static final TypeReference<ArrayList<DeviceAlertDto>> ALERTS_TYPE = new TypeReference<>() {};

    private final ObjectMapper mapper = JsonMapper.builder().build();

    /**
     * Serializes the override list.
     *
     * @param overrides port names; may be {@code null}
     * @return the JSON array ({@code "[]"} for null or empty)
     */
    public String serialize(List<String> overrides) {
        if (overrides == null || overrides.isEmpty()) {
            return "[]";
        }
        return mapper.writeValueAsString(overrides);
    }

    /**
     * Serializes the alert list of a heartbeat.
     *
     * @param alerts reported alerts; may be {@code null}
     * @return the JSON array ({@code "[]"} for null or empty)
     */
    public String serializeAlerts(List<DeviceAlertDto> alerts) {
        if (alerts == null || alerts.isEmpty()) {
            return "[]";
        }
        return mapper.writeValueAsString(alerts);
    }

    /**
     * Deserializes the stored alerts; a corrupt value is logged and read as empty.
     *
     * @param json the stored {@code last_alerts} value; may be {@code null} or blank
     * @return the alerts (never {@code null})
     */
    public List<DeviceAlertDto> deserializeAlerts(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return List.copyOf(mapper.readValue(json, ALERTS_TYPE));
        } catch (JacksonException e) {
            log.warn("device_alerts_corrupt json={}", json);
            return List.of();
        }
    }

    /**
     * Deserializes the stored JSON back into a list. A corrupt value is logged and read as empty
     * rather than failing the whole device listing.
     *
     * @param json the stored {@code last_overrides} value; may be {@code null} or blank
     * @return the override list (never {@code null})
     */
    public List<String> deserialize(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return List.copyOf(mapper.readValue(json, LIST_TYPE));
        } catch (JacksonException e) {
            log.warn("device_overrides_corrupt json={}", json);
            return List.of();
        }
    }
}
