package com.stocka.backend.modules.piecetypes.dto;

import java.util.Date;
import java.util.List;

import com.stocka.backend.modules.piecetypes.entity.PieceType;

/**
 * Piece type with its full attribute list, ordered by {@code position}. {@code kind} is the output
 * kind catalog id the type drives, or {@code null} for inventory-only types.
 */
public record PieceTypeResponseDto(
        Integer id,
        Integer organizationId,
        String name,
        String kind,
        Date createdAt,
        Date updatedAt,
        List<PieceTypeAttributeResponseDto> attributes
) {
    public static PieceTypeResponseDto from(PieceType type, List<PieceTypeAttributeResponseDto> attributes) {
        return new PieceTypeResponseDto(
                type.getId(),
                type.getOrganization().getId(),
                type.getName(),
                type.getKind() == null ? null : type.getKind().catalogId(),
                type.getCreatedAt(),
                type.getUpdatedAt(),
                attributes
        );
    }
}
