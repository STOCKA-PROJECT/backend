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
        AttributeValueValidator validator = byType.get(attribute.getType());
        if (validator == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "No hay validador registrado para el tipo " + attribute.getType());
        }
        AttributeValidatorsDto rules = validatorsCodec.deserialize(attribute.getValidatorsJson());
        return validator.validateAndNormalize(rawValue, rules, attribute.isRequired());
    }
}
