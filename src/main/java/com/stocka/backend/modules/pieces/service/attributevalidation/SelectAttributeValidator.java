package com.stocka.backend.modules.pieces.service.attributevalidation;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import com.stocka.backend.modules.piecetypes.dto.AttributeValidatorsDto;
import com.stocka.backend.modules.piecetypes.entity.AttributeType;

/** Single-choice. Value must be one of {@code rules.options}. */
@Component
public class SelectAttributeValidator extends AbstractAttributeValueValidator {

    @Override
    public AttributeType supports() {
        return AttributeType.SELECT;
    }

    @Override
    public String validateAndNormalize(String raw, AttributeValidatorsDto rules, boolean required) {
        String checked = preCheck(raw, required);
        if (checked == null) return null;
        List<String> options = rules.getOptions();
        if (options == null || options.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "El atributo SELECT no tiene opciones configuradas");
        }
        String trimmed = checked.trim();
        if (!options.contains(trimmed)) {
            throw badRequest("El valor debe estar entre las opciones permitidas: " + options);
        }
        return trimmed;
    }
}
