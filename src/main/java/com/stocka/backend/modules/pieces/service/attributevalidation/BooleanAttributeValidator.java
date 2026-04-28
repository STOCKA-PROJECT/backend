package com.stocka.backend.modules.pieces.service.attributevalidation;

import java.util.Locale;

import org.springframework.stereotype.Component;

import com.stocka.backend.modules.piecetypes.dto.AttributeValidatorsDto;
import com.stocka.backend.modules.piecetypes.entity.AttributeType;

/** Boolean. Accepts {@code true}/{@code false} (case-insensitive). Rejects {@code yes}/{@code 1}/etc. */
@Component
public class BooleanAttributeValidator extends AbstractAttributeValueValidator {

    @Override
    public AttributeType supports() {
        return AttributeType.BOOLEAN;
    }

    @Override
    public String validateAndNormalize(String raw, AttributeValidatorsDto rules, boolean required) {
        String checked = preCheck(raw, required);
        if (checked == null) return null;
        String lowered = checked.trim().toLowerCase(Locale.ROOT);
        if (!lowered.equals("true") && !lowered.equals("false")) {
            throw badRequest("El valor debe ser 'true' o 'false'");
        }
        return lowered;
    }
}
