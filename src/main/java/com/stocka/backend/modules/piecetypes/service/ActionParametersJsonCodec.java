package com.stocka.backend.modules.piecetypes.service;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import com.stocka.backend.modules.piecetypes.dto.ActionParameterDto;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Round-trips an action's parameter list between its JSON form (stored as the
 * {@code parameters_json} TEXT column) and its strongly-typed {@link ActionParameterDto} list.
 *
 * <p>Mirrors {@link ValidatorsJsonCodec}, keeping the choice of Jackson 3 (the
 * {@code tools.jackson.*} namespace shipped by Spring Boot 4) in a single place.
 */
@Component
public class ActionParametersJsonCodec {
    private static final TypeReference<List<ActionParameterDto>> LIST_TYPE = new TypeReference<>() {};

    private final ObjectMapper mapper = JsonMapper.builder().build();

    /**
     * Serializes the parameter list into its JSON representation.
     *
     * @param parameters parameter definitions; may be {@code null}
     * @return the JSON string, or {@code null} when {@code parameters} is {@code null}
     */
    public String serialize(List<ActionParameterDto> parameters) {
        if (parameters == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(parameters);
        } catch (JacksonException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No se pudieron serializar los parámetros de la acción");
        }
    }

    /**
     * Deserializes the stored JSON back into a parameter list.
     *
     * @param json the stored {@code parameters_json} value; may be {@code null} or blank
     * @return the parameter list, or an empty list when no parameters are stored
     */
    public List<ActionParameterDto> deserialize(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(json, LIST_TYPE);
        } catch (JacksonException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Parámetros de acción corruptos");
        }
    }
}
