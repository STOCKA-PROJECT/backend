package com.stocka.backend.modules.pieces.dto;

import com.stocka.backend.modules.pieces.entity.PieceAttributeValue;
import com.stocka.backend.modules.pieces.entity.PieceOrganizationAttributeValue;
import com.stocka.backend.modules.piecetypes.entity.AttributeType;

/**
 * Single attribute value in a piece detail response. Carries a {@link AttributeScope} so callers
 * can distinguish values bound to a piece-type-level attribute from values bound to an
 * organization-level attribute when both share the response array.
 */
public record PieceAttributeValueResponseDto(
        Integer attributeId,
        AttributeScope scope,
        String attributeName,
        String displayName,
        AttributeType type,
        String value
) {
    public static PieceAttributeValueResponseDto fromType(PieceAttributeValue value) {
        return new PieceAttributeValueResponseDto(
                value.getAttribute().getId(),
                AttributeScope.TYPE,
                value.getAttribute().getName(),
                value.getAttribute().getDisplayName(),
                value.getAttribute().getType(),
                value.getValue()
        );
    }

    public static PieceAttributeValueResponseDto fromOrg(PieceOrganizationAttributeValue value) {
        return new PieceAttributeValueResponseDto(
                value.getAttribute().getId(),
                AttributeScope.ORG,
                value.getAttribute().getName(),
                value.getAttribute().getDisplayName(),
                value.getAttribute().getType(),
                value.getValue()
        );
    }
}
