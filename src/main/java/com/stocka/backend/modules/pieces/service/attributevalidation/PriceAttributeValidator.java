package com.stocka.backend.modules.pieces.service.attributevalidation;

import java.math.BigDecimal;

import org.springframework.stereotype.Component;

import com.stocka.backend.modules.piecetypes.dto.AttributeValidatorsDto;
import com.stocka.backend.modules.piecetypes.entity.AttributeType;

/**
 * Money amount. Defaults: {@code min=0}, {@code decimals=2}. The currency code (if provided in
 * {@code rules.currency}) is informational and stored alongside the value as part of the
 * normalized form ({@code "12.50 EUR"}).
 */
@Component
public class PriceAttributeValidator extends AbstractAttributeValueValidator {
    private static final int DEFAULT_DECIMALS = 2;

    @Override
    public AttributeType supports() {
        return AttributeType.PRICE;
    }

    @Override
    public String validateAndNormalize(String raw, AttributeValidatorsDto rules, boolean required) {
        String checked = preCheck(raw, required);
        if (checked == null) return null;

        // raw may be "12.50" or "12.50 EUR"; we strip the optional currency token and validate the number part.
        String trimmed = checked.trim();
        String numberPart = trimmed;
        String currencyOverride = null;
        int firstSpace = trimmed.indexOf(' ');
        if (firstSpace > 0) {
            numberPart = trimmed.substring(0, firstSpace).trim();
            currencyOverride = trimmed.substring(firstSpace + 1).trim();
        }

        BigDecimal parsed;
        try {
            parsed = new BigDecimal(numberPart);
        } catch (NumberFormatException e) {
            throw badRequest("El precio debe ser un número decimal válido");
        }

        BigDecimal min = rules.getMin() == null ? BigDecimal.ZERO : rules.getMin();
        if (parsed.compareTo(min) < 0) {
            throw badRequest("El precio debe ser >= " + min);
        }
        if (rules.getMax() != null && parsed.compareTo(rules.getMax()) > 0) {
            throw badRequest("El precio debe ser <= " + rules.getMax());
        }
        int decimals = rules.getDecimals() == null ? DEFAULT_DECIMALS : rules.getDecimals();
        int scale = parsed.stripTrailingZeros().scale();
        if (scale > decimals) {
            throw badRequest("El precio no puede tener más de " + decimals + " decimales");
        }

        String currency = currencyOverride != null ? currencyOverride : rules.getCurrency();
        if (currency != null && !currency.isBlank()) {
            return parsed.setScale(decimals, java.math.RoundingMode.UNNECESSARY).toPlainString() + " " + currency.trim().toUpperCase(java.util.Locale.ROOT);
        }
        return parsed.setScale(decimals, java.math.RoundingMode.UNNECESSARY).toPlainString();
    }
}
