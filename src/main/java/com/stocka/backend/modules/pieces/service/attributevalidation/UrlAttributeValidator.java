package com.stocka.backend.modules.pieces.service.attributevalidation;

import java.net.URI;
import java.net.URISyntaxException;

import org.springframework.stereotype.Component;

import com.stocka.backend.modules.piecetypes.dto.AttributeValidatorsDto;
import com.stocka.backend.modules.piecetypes.entity.AttributeType;

/** Absolute http/https URL. */
@Component
public class UrlAttributeValidator extends AbstractAttributeValueValidator {
    private static final int DEFAULT_MAX_LENGTH = 2048;

    @Override
    public AttributeType supports() {
        return AttributeType.URL;
    }

    @Override
    public String validateAndNormalize(String raw, AttributeValidatorsDto rules, boolean required) {
        String checked = preCheck(raw, required);
        if (checked == null) return null;
        String value = checked.trim();
        int max = rules.getMaxLength() == null ? DEFAULT_MAX_LENGTH : rules.getMaxLength();
        if (value.length() > max) {
            throw badRequest("La URL no puede superar " + max + " caracteres");
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            throw badRequest("La URL no es válida");
        }
        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            throw badRequest("La URL debe empezar por http:// o https://");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw badRequest("La URL debe incluir un dominio");
        }
        return value;
    }
}
