package com.stocka.backend.modules.piecetypes.dto;

import com.stocka.backend.modules.piecetypes.entity.AttributeType;
import com.stocka.backend.modules.piecetypes.entity.PieceTypeAttribute;

/**
 * Single attribute exposed in REST responses, with its raw {@code validators} blob deserialized
 * back into the strongly-typed {@link AttributeValidatorsDto}.
 */
public record PieceTypeAttributeResponseDto(
        Integer id,
        String name,
        String displayName,
        AttributeType type,
        boolean required,
        int position,
        AttributeValidatorsDto validators
) {
    public static PieceTypeAttributeResponseDto from(PieceTypeAttribute attr, AttributeValidatorsDto validators) {
        return new PieceTypeAttributeResponseDto(
                attr.getId(),
                attr.getName(),
                attr.getDisplayName(),
                attr.getType(),
                attr.isRequired(),
                attr.getPosition(),
                validators
        );
    }
}
