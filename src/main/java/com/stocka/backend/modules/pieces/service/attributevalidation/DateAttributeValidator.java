package com.stocka.backend.modules.pieces.service.attributevalidation;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

import org.springframework.stereotype.Component;

import com.stocka.backend.modules.piecetypes.dto.AttributeValidatorsDto;
import com.stocka.backend.modules.piecetypes.entity.AttributeType;

/** ISO-8601 date ({@code YYYY-MM-DD}). Optional rules: {@code minDate}, {@code maxDate}, allowFuture/allowPast. */
@Component
public class DateAttributeValidator extends AbstractAttributeValueValidator {

    @Override
    public AttributeType supports() {
        return AttributeType.DATE;
    }

    @Override
    public String validateAndNormalize(String raw, AttributeValidatorsDto rules, boolean required) {
        String checked = preCheck(raw, required);
        if (checked == null) return null;
        LocalDate parsed;
        try {
            parsed = LocalDate.parse(checked.trim());
        } catch (DateTimeParseException e) {
            throw badRequest("La fecha debe estar en formato ISO YYYY-MM-DD");
        }
        if (rules.getMinDate() != null) {
            try {
                LocalDate min = LocalDate.parse(rules.getMinDate());
                if (parsed.isBefore(min)) {
                    throw badRequest("La fecha debe ser posterior o igual a " + min);
                }
            } catch (DateTimeParseException ignored) {
                // bad config: silent
            }
        }
        if (rules.getMaxDate() != null) {
            try {
                LocalDate max = LocalDate.parse(rules.getMaxDate());
                if (parsed.isAfter(max)) {
                    throw badRequest("La fecha debe ser anterior o igual a " + max);
                }
            } catch (DateTimeParseException ignored) {
            }
        }
        LocalDate today = LocalDate.now();
        if (Boolean.FALSE.equals(rules.getAllowFuture()) && parsed.isAfter(today)) {
            throw badRequest("La fecha no puede ser futura");
        }
        if (Boolean.FALSE.equals(rules.getAllowPast()) && parsed.isBefore(today)) {
            throw badRequest("La fecha no puede ser pasada");
        }
        return parsed.toString();
    }
}
