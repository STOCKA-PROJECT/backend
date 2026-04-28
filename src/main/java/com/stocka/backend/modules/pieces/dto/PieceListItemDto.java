package com.stocka.backend.modules.pieces.dto;

import java.util.Date;

import com.stocka.backend.modules.pieces.entity.Piece;
import com.stocka.backend.modules.pieces.entity.PieceStatus;

/** Compact view of a piece for paginated listings. */
public record PieceListItemDto(
        Integer id,
        String name,
        Integer pieceTypeId,
        String pieceTypeName,
        Integer ownerUserId,
        Integer locationId,
        PieceStatus status,
        Date createdAt,
        Date updatedAt
) {
    public static PieceListItemDto from(Piece piece) {
        return new PieceListItemDto(
                piece.getId(),
                piece.getName(),
                piece.getPieceType().getId(),
                piece.getPieceType().getName(),
                piece.getOwner() == null ? null : piece.getOwner().getId(),
                piece.getLocation() == null ? null : piece.getLocation().getId(),
                piece.getStatus(),
                piece.getCreatedAt(),
                piece.getUpdatedAt()
        );
    }
}
