package com.stocka.backend.modules.pieces.dto;

import com.stocka.backend.modules.pieces.entity.PieceAttributeValue;
import com.stocka.backend.modules.piecetypes.entity.AttributeType;

/** Single attribute value in a piece detail response. */
public record PieceAttributeValueResponseDto(
        Integer attributeId,
        String attributeName,
        String displayName,
        AttributeType type,
        String value
) {
    public static PieceAttributeValueResponseDto from(PieceAttributeValue value) {
        return new PieceAttributeValueResponseDto(
                value.getAttribute().getId(),
                value.getAttribute().getName(),
                value.getAttribute().getDisplayName(),
                value.getAttribute().getType(),
                value.getValue()
        );
    }
}
