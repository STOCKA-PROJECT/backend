package com.stocka.backend.modules.pieces.dto;

import java.util.Date;
import java.util.List;

import com.stocka.backend.modules.pieces.entity.Piece;
import com.stocka.backend.modules.pieces.entity.PieceStatus;

/** Full piece detail with values and attachments. */
public record PieceResponseDto(
        Integer id,
        Integer organizationId,
        String name,
        String description,
        Integer pieceTypeId,
        String pieceTypeName,
        Integer ownerUserId,
        Integer locationId,
        PieceStatus status,
        Date createdAt,
        Date updatedAt,
        List<PieceAttributeValueResponseDto> attributeValues,
        List<PieceAttachmentResponseDto> attachments
) {
    public static PieceResponseDto from(Piece piece,
                                        List<PieceAttributeValueResponseDto> values,
                                        List<PieceAttachmentResponseDto> attachments) {
        return new PieceResponseDto(
                piece.getId(),
                piece.getOrganization().getId(),
                piece.getName(),
                piece.getDescription(),
                piece.getPieceType().getId(),
                piece.getPieceType().getName(),
                piece.getOwner() == null ? null : piece.getOwner().getId(),
                piece.getLocation() == null ? null : piece.getLocation().getId(),
                piece.getStatus(),
                piece.getCreatedAt(),
                piece.getUpdatedAt(),
                values,
                attachments
        );
    }
}
