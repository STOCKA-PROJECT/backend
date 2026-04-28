package com.stocka.backend.modules.piecetypes.dto;

import java.util.Date;
import java.util.List;

import com.stocka.backend.modules.piecetypes.entity.PieceType;

/**
 * Piece type with its full attribute list, ordered by {@code position}.
 */
public record PieceTypeResponseDto(
        Integer id,
        Integer organizationId,
        String name,
        Date createdAt,
        Date updatedAt,
        List<PieceTypeAttributeResponseDto> attributes
) {
    public static PieceTypeResponseDto from(PieceType type, List<PieceTypeAttributeResponseDto> attributes) {
        return new PieceTypeResponseDto(
                type.getId(),
                type.getOrganization().getId(),
                type.getName(),
                type.getCreatedAt(),
                type.getUpdatedAt(),
                attributes
        );
    }
}
