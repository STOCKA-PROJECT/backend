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
        String serialNumber,
        String description,
        List<PieceTypeRefDto> pieceTypes,
        Integer ownerUserId,
        Integer locationId,
        Integer coverAttachmentId,
        PieceStatus status,
        Date createdAt,
        Date updatedAt,
        List<PieceAttributeValueResponseDto> attributeValues,
        List<PieceAttachmentResponseDto> attachments
) {
    public static PieceResponseDto from(Piece piece,
                                        List<PieceAttributeValueResponseDto> values,
                                        List<PieceAttachmentResponseDto> attachments) {
        List<PieceTypeRefDto> types = piece.getPieceTypes().stream()
                .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                .map(PieceTypeRefDto::from)
                .toList();
        return new PieceResponseDto(
                piece.getId(),
                piece.getOrganization().getId(),
                piece.getName(),
                piece.getSerialNumber(),
                piece.getDescription(),
                types,
                piece.getOwner() == null ? null : piece.getOwner().getId(),
                piece.getLocation() == null ? null : piece.getLocation().getId(),
                piece.getCoverAttachment() == null ? null : piece.getCoverAttachment().getId(),
                piece.getStatus(),
                piece.getCreatedAt(),
                piece.getUpdatedAt(),
                values,
                attachments
        );
    }
}
