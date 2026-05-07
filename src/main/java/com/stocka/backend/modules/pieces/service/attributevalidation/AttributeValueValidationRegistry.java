package com.stocka.backend.modules.pieces.service.attributevalidation;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import com.stocka.backend.modules.piecetypes.dto.AttributeValidatorsDto;
import com.stocka.backend.modules.piecetypes.entity.AttributeType;
import com.stocka.backend.modules.piecetypes.entity.PieceTypeAttribute;
import com.stocka.backend.modules.piecetypes.service.ValidatorsJsonCodec;

/**
 * Look-up table from {@link AttributeType} to its {@link AttributeValueValidator}. Spring injects
 * every validator bean and we index them once at startup.
 */
@Component
public class AttributeValueValidationRegistry {
    private final Map<AttributeType, AttributeValueValidator> byType;
    private final ValidatorsJsonCodec validatorsCodec;

    public AttributeValueValidationRegistry(
            List<AttributeValueValidator> validators,
            ValidatorsJsonCodec validatorsCodec
    ) {
        this.byType = validators.stream()
                .collect(Collectors.toUnmodifiableMap(AttributeValueValidator::supports, Function.identity()));
        this.validatorsCodec = validatorsCodec;
    }

    /**
     * Validates {@code rawValue} against {@code attribute}'s configuration.
     *
     * @return the normalized string ready to store in {@code piece_attribute_values.value}, or
     *         {@code null} if the optional attribute received no value
     */
    public String validate(PieceTypeAttribute attribute, String rawValue) {
        return validate(attribute.getType(), attribute.getValidatorsJson(), attribute.isRequired(), rawValue);
    }

    /**
     * Type-agnostic validation entry-point shared by piece-type attributes and organization-level
     * attributes. Decouples the registry from a specific entity class.
     *
     * @param type           attribute type that selects the matching validator strategy
     * @param validatorsJson serialized validator blob; may be {@code null}
     * @param required       whether a missing value should be reported as a validation error
     * @param rawValue       raw user input to normalize
     * @return normalized canonical value or {@code null} when the optional attribute has none
     */
    public String validate(AttributeType type, String validatorsJson, boolean required, String rawValue) {
        AttributeValueValidator validator = byType.get(type);
        if (validator == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "No hay validador registrado para el tipo " + type);
        }
        AttributeValidatorsDto rules = validatorsCodec.deserialize(validatorsJson);
        return validator.validateAndNormalize(rawValue, rules, required);
    }
}
