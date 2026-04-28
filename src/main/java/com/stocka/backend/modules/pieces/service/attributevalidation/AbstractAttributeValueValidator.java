package com.stocka.backend.modules.pieces.service.attributevalidation;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Common helpers shared by every validator: handles the required/optional guard so concrete
 * implementations only worry about format/range.
 */
abstract class AbstractAttributeValueValidator implements AttributeValueValidator {

    /**
     * @return {@code raw} after a {@code null}/blank check; {@code null} if optional and empty
     * @throws ResponseStatusException 400 if required and empty
     */
    protected String preCheck(String raw, boolean required) {
        if (raw == null || raw.isBlank()) {
            if (required) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "El atributo es obligatorio");
            }
            return null;
        }
        return raw;
    }

    protected ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
