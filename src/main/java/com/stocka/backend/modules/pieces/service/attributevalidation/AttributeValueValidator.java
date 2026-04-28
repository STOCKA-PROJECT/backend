package com.stocka.backend.modules.pieces.service.attributevalidation;

import org.springframework.web.server.ResponseStatusException;

import com.stocka.backend.modules.piecetypes.dto.AttributeValidatorsDto;
import com.stocka.backend.modules.piecetypes.entity.AttributeType;

/**
 * Strategy that validates and normalizes the raw value provided for a single
 * {@link com.stocka.backend.modules.piecetypes.entity.PieceTypeAttribute}. One implementation
 * exists per {@link AttributeType} value.
 *
 * <p>All implementations are wired by Spring and aggregated in
 * {@link AttributeValueValidationRegistry}, which dispatches to the right one based on
 * {@link #supports()}.
 */
public interface AttributeValueValidator {

    /**
     * @return the {@link AttributeType} this validator handles
     */
    AttributeType supports();

    /**
     * Validates {@code raw} against {@code rules} and returns the canonical string to persist.
     *
     * <ul>
     *   <li>If {@code raw} is null/blank and {@code required} is true → throws 400.</li>
     *   <li>If {@code raw} is null/blank and {@code required} is false → returns {@code null}.</li>
     *   <li>If {@code raw} is non-blank → returns the normalized representation.</li>
     * </ul>
     *
     * @param raw      raw user input (possibly null/blank)
     * @param rules    validators for this attribute (never null; may be empty)
     * @param required whether the attribute is mandatory
     * @return canonical string ready to be stored, or {@code null} when the optional value is empty
     * @throws ResponseStatusException 400 with a Spanish message on any validation failure
     */
    String validateAndNormalize(String raw, AttributeValidatorsDto rules, boolean required);
}
