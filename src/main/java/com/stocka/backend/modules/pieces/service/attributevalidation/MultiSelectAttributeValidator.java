package com.stocka.backend.modules.pieces.service.attributevalidation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import com.stocka.backend.modules.piecetypes.dto.AttributeValidatorsDto;
import com.stocka.backend.modules.piecetypes.entity.AttributeType;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Multi-choice. Accepts either a JSON array (e.g. {@code ["a","b"]}) or a comma-separated string
 * (e.g. {@code "a, b"}). Always normalized into a JSON array string for storage.
 */
@Component
public class MultiSelectAttributeValidator extends AbstractAttributeValueValidator {
    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Override
    public AttributeType supports() {
        return AttributeType.MULTI_SELECT;
    }

    @Override
    public String validateAndNormalize(String raw, AttributeValidatorsDto rules, boolean required) {
        String checked = preCheck(raw, required);
        if (checked == null) return null;
        List<String> options = rules.getOptions();
        if (options == null || options.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "El atributo MULTI_SELECT no tiene opciones configuradas");
        }
        List<String> values = parse(checked);
        if (values.isEmpty() && required) {
            throw badRequest("Debes seleccionar al menos una opción");
        }
        for (String v : values) {
            if (!options.contains(v)) {
                throw badRequest("Valor no permitido: " + v + ". Opciones: " + options);
            }
        }
        if (rules.getMinItems() != null && values.size() < rules.getMinItems()) {
            throw badRequest("Debes seleccionar al menos " + rules.getMinItems() + " opciones");
        }
        if (rules.getMaxItems() != null && values.size() > rules.getMaxItems()) {
            throw badRequest("No puedes seleccionar más de " + rules.getMaxItems() + " opciones");
        }
        try {
            return mapper.writeValueAsString(values);
        } catch (JacksonException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "No se pudo serializar el valor del atributo");
        }
    }

    private List<String> parse(String raw) {
        String trimmed = raw.trim();
        if (trimmed.startsWith("[")) {
            try {
                List<?> list = mapper.readValue(trimmed, List.class);
                List<String> out = new ArrayList<>(list.size());
                for (Object o : list) {
                    if (o == null) {
                        throw badRequest("Las opciones del valor no pueden ser null");
                    }
                    out.add(o.toString());
                }
                return out;
            } catch (JacksonException e) {
                throw badRequest("El valor debe ser un array JSON o una lista separada por comas");
            }
        }
        return Arrays.stream(trimmed.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
