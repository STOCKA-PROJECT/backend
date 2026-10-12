package com.stocka.backend.modules.ports.service;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Round-trips a port's kind-specific config between its JSON form (the {@code config_json} TEXT
 * column) and an ordered {@code Map<String, Object>}. Mirrors
 * {@code ActionParametersJsonCodec}, keeping Jackson 3 usage in one place.
 */
@Component
public class PortConfigJsonCodec {
    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ObjectMapper mapper = JsonMapper.builder().build();

    /**
     * Serializes the config object.
     *
     * @param config config keys; may be {@code null}
     * @return the JSON string ({@code "{}"} for null or empty)
     */
    public String serialize(Map<String, Object> config) {
        if (config == null || config.isEmpty()) {
            return "{}";
        }
        try {
            return mapper.writeValueAsString(config);
        } catch (JacksonException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No s'ha pogut serialitzar la configuració del port");
        }
    }

    /**
     * Deserializes the stored JSON back into an ordered map.
     *
     * @param json the stored {@code config_json} value; may be {@code null} or blank
     * @return the config map (empty when nothing is stored)
     */
    public Map<String, Object> deserialize(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return mapper.readValue(json, MAP_TYPE);
        } catch (JacksonException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Configuració del port corrupta");
        }
    }
}
