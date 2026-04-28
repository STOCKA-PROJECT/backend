package com.stocka.backend.modules.pieces.service.attributevalidation;

import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.stocka.backend.modules.piecetypes.dto.AttributeValidatorsDto;
import com.stocka.backend.modules.piecetypes.entity.AttributeType;

/** RFC-light email validation. */
@Component
public class EmailAttributeValidator extends AbstractAttributeValueValidator {
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$"
    );
    private static final int DEFAULT_MAX_LENGTH = 254;

    @Override
    public AttributeType supports() {
        return AttributeType.EMAIL;
    }

    @Override
    public String validateAndNormalize(String raw, AttributeValidatorsDto rules, boolean required) {
        String checked = preCheck(raw, required);
        if (checked == null) return null;
        String value = checked.trim();
        int max = rules.getMaxLength() == null ? DEFAULT_MAX_LENGTH : rules.getMaxLength();
        if (value.length() > max) {
            throw badRequest("El email no puede superar " + max + " caracteres");
        }
        if (!EMAIL_PATTERN.matcher(value).matches()) {
            throw badRequest("El email no tiene un formato válido");
        }
        return value;
    }
}
