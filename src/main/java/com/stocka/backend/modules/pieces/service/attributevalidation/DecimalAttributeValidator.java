package com.stocka.backend.modules.pieces.service.attributevalidation;

import java.math.BigDecimal;

import org.springframework.stereotype.Component;

import com.stocka.backend.modules.piecetypes.dto.AttributeValidatorsDto;
import com.stocka.backend.modules.piecetypes.entity.AttributeType;

/** Arbitrary-precision decimal stored using {@link BigDecimal#toPlainString()}. */
@Component
public class DecimalAttributeValidator extends AbstractAttributeValueValidator {

    @Override
    public AttributeType supports() {
        return AttributeType.DECIMAL;
    }

    @Override
    public String validateAndNormalize(String raw, AttributeValidatorsDto rules, boolean required) {
        String checked = preCheck(raw, required);
        if (checked == null) return null;
        BigDecimal parsed;
        try {
            parsed = new BigDecimal(checked.trim());
        } catch (NumberFormatException e) {
            throw badRequest("El valor debe ser un número decimal válido");
        }
        if (rules.getMin() != null && parsed.compareTo(rules.getMin()) < 0) {
            throw badRequest("El valor debe ser >= " + rules.getMin());
        }
        if (rules.getMax() != null && parsed.compareTo(rules.getMax()) > 0) {
            throw badRequest("El valor debe ser <= " + rules.getMax());
        }
        if (rules.getDecimals() != null && rules.getDecimals() >= 0) {
            int scale = parsed.stripTrailingZeros().scale();
            if (scale > rules.getDecimals()) {
                throw badRequest("El valor no puede tener más de " + rules.getDecimals() + " decimales");
            }
        }
        return parsed.toPlainString();
    }
}
