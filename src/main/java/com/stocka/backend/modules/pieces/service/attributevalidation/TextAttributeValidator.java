package com.stocka.backend.modules.pieces.service.attributevalidation;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.springframework.stereotype.Component;

import com.stocka.backend.modules.piecetypes.dto.AttributeValidatorsDto;
import com.stocka.backend.modules.piecetypes.entity.AttributeType;

/** Single-line text. Defaults: maxLength=255. */
@Component
public class TextAttributeValidator extends AbstractAttributeValueValidator {
    private static final int DEFAULT_MAX_LENGTH = 255;

    @Override
    public AttributeType supports() {
        return AttributeType.TEXT;
    }

    @Override
    public String validateAndNormalize(String raw, AttributeValidatorsDto rules, boolean required) {
        String checked = preCheck(raw, required);
        if (checked == null) return null;
        String value = checked.trim();

        int min = rules.getMinLength() == null ? 0 : rules.getMinLength();
        int max = rules.getMaxLength() == null ? DEFAULT_MAX_LENGTH : rules.getMaxLength();
        if (value.length() < min) {
            throw badRequest("El texto debe tener al menos " + min + " caracteres");
        }
        if (value.length() > max) {
            throw badRequest("El texto no puede superar " + max + " caracteres");
        }
        if (rules.getRegex() != null && !rules.getRegex().isBlank()) {
            try {
                if (!Pattern.compile(rules.getRegex()).matcher(value).matches()) {
                    throw badRequest("El texto no respeta el formato esperado");
                }
            } catch (PatternSyntaxException e) {
                throw badRequest("El validador del atributo tiene una expresión regular inválida");
            }
        }
        return value;
    }
}
