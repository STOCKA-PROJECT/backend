package com.stocka.backend.modules.organizations.dto;

import com.stocka.backend.modules.organizations.entity.OrganizationPieceAttribute;
import com.stocka.backend.modules.piecetypes.dto.AttributeValidatorsDto;
import com.stocka.backend.modules.piecetypes.entity.AttributeType;

/**
 * Single organization-level piece attribute exposed in REST responses, with its raw
 * {@code validators} blob deserialized back into the strongly-typed
 * {@link AttributeValidatorsDto}.
 */
public record OrganizationPieceAttributeResponseDto(
        Integer id,
        String name,
        String displayName,
        AttributeType type,
        boolean required,
        int position,
        AttributeValidatorsDto validators
) {
    public static OrganizationPieceAttributeResponseDto from(OrganizationPieceAttribute attr,
                                                             AttributeValidatorsDto validators) {
        return new OrganizationPieceAttributeResponseDto(
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
