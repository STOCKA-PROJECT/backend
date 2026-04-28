package com.stocka.backend.modules.pieces.service.attributevalidation;

import org.springframework.stereotype.Component;

import com.stocka.backend.modules.piecetypes.dto.AttributeValidatorsDto;
import com.stocka.backend.modules.piecetypes.entity.AttributeType;

/** Multi-line text. Defaults: maxLength=10_000. No regex applied. */
@Component
public class LongTextAttributeValidator extends AbstractAttributeValueValidator {
    private static final int DEFAULT_MAX_LENGTH = 10_000;

    @Override
    public AttributeType supports() {
        return AttributeType.LONGTEXT;
    }

    @Override
    public String validateAndNormalize(String raw, AttributeValidatorsDto rules, boolean required) {
        String checked = preCheck(raw, required);
        if (checked == null) return null;
        int min = rules.getMinLength() == null ? 0 : rules.getMinLength();
        int max = rules.getMaxLength() == null ? DEFAULT_MAX_LENGTH : rules.getMaxLength();
        if (checked.length() < min) {
            throw badRequest("El texto debe tener al menos " + min + " caracteres");
        }
        if (checked.length() > max) {
            throw badRequest("El texto no puede superar " + max + " caracteres");
        }
        return checked;
    }
}
