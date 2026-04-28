package com.stocka.backend.modules.piecetypes.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import com.stocka.backend.modules.piecetypes.dto.AttributeValidatorsDto;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Round-trips {@link AttributeValidatorsDto} between its JSON form (stored as the
 * {@code validators_json} TEXT column) and its strongly-typed Java representation.
 *
 * <p>Centralized to keep the choice of Jackson 3 (the {@code tools.jackson.*} namespace shipped
 * by Spring Boot 4) in a single place.
 */
@Component
public class ValidatorsJsonCodec {
    private final ObjectMapper mapper = JsonMapper.builder().build();

    public String serialize(AttributeValidatorsDto rules) {
        if (rules == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(rules);
        } catch (JacksonException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No se pudieron serializar los validadores del atributo");
        }
    }

    public AttributeValidatorsDto deserialize(String json) {
        if (json == null || json.isBlank()) {
            return new AttributeValidatorsDto();
        }
        try {
            return mapper.readValue(json, AttributeValidatorsDto.class);
        } catch (JacksonException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Validadores corruptos en el atributo");
        }
    }
}
