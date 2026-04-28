package com.stocka.backend.modules.pieces.service.attributevalidation;

import java.math.BigDecimal;

import org.springframework.stereotype.Component;

import com.stocka.backend.modules.piecetypes.dto.AttributeValidatorsDto;
import com.stocka.backend.modules.piecetypes.entity.AttributeType;

/** Integer values stored as their canonical {@code long} {@link Long#toString()} form. */
@Component
public class IntegerAttributeValidator extends AbstractAttributeValueValidator {

    @Override
    public AttributeType supports() {
        return AttributeType.INTEGER;
    }

    @Override
    public String validateAndNormalize(String raw, AttributeValidatorsDto rules, boolean required) {
        String checked = preCheck(raw, required);
        if (checked == null) return null;
        long parsed;
        try {
            parsed = Long.parseLong(checked.trim());
        } catch (NumberFormatException e) {
            throw badRequest("El valor debe ser un número entero");
        }
        if (rules.getMin() != null && BigDecimal.valueOf(parsed).compareTo(rules.getMin()) < 0) {
            throw badRequest("El valor debe ser >= " + rules.getMin());
        }
        if (rules.getMax() != null && BigDecimal.valueOf(parsed).compareTo(rules.getMax()) > 0) {
            throw badRequest("El valor debe ser <= " + rules.getMax());
        }
        return Long.toString(parsed);
    }
}
