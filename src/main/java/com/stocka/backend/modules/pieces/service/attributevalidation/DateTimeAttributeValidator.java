package com.stocka.backend.modules.pieces.service.attributevalidation;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

import org.springframework.stereotype.Component;

import com.stocka.backend.modules.piecetypes.dto.AttributeValidatorsDto;
import com.stocka.backend.modules.piecetypes.entity.AttributeType;

/** ISO-8601 timestamp without offset ({@code YYYY-MM-DDTHH:MM:SS}). */
@Component
public class DateTimeAttributeValidator extends AbstractAttributeValueValidator {

    @Override
    public AttributeType supports() {
        return AttributeType.DATETIME;
    }

    @Override
    public String validateAndNormalize(String raw, AttributeValidatorsDto rules, boolean required) {
        String checked = preCheck(raw, required);
        if (checked == null) return null;
        LocalDateTime parsed;
        try {
            parsed = LocalDateTime.parse(checked.trim());
        } catch (DateTimeParseException e) {
            throw badRequest("La fecha-hora debe estar en formato ISO YYYY-MM-DDTHH:MM:SS");
        }
        if (rules.getMinDate() != null) {
            try {
                LocalDate min = LocalDate.parse(rules.getMinDate());
                if (parsed.toLocalDate().isBefore(min)) {
                    throw badRequest("La fecha debe ser posterior o igual a " + min);
                }
            } catch (DateTimeParseException ignored) {}
        }
        if (rules.getMaxDate() != null) {
            try {
                LocalDate max = LocalDate.parse(rules.getMaxDate());
                if (parsed.toLocalDate().isAfter(max)) {
                    throw badRequest("La fecha debe ser anterior o igual a " + max);
                }
            } catch (DateTimeParseException ignored) {}
        }
        LocalDateTime now = LocalDateTime.now();
        if (Boolean.FALSE.equals(rules.getAllowFuture()) && parsed.isAfter(now)) {
            throw badRequest("La fecha-hora no puede ser futura");
        }
        if (Boolean.FALSE.equals(rules.getAllowPast()) && parsed.isBefore(now)) {
            throw badRequest("La fecha-hora no puede ser pasada");
        }
        return parsed.toString();
    }
}
