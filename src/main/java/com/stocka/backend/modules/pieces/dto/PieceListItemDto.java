package com.stocka.backend.modules.pieces.dto;

import java.util.Date;
import java.util.List;

import com.stocka.backend.modules.pieces.entity.Piece;
import com.stocka.backend.modules.pieces.entity.PieceStatus;
import com.stocka.backend.modules.piecetypes.entity.PieceType;

/** Compact view of a piece for paginated listings. */
public record PieceListItemDto(
        Integer id,
        String name,
        List<PieceTypeRefDto> pieceTypes,
        Integer ownerUserId,
        Integer locationId,
        PieceStatus status,
        Date createdAt,
        Date updatedAt
) {
    public static PieceListItemDto from(Piece piece) {
        List<PieceTypeRefDto> types = piece.getPieceTypes().stream()
                .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                .map(PieceTypeRefDto::from)
                .toList();
        return new PieceListItemDto(
                piece.getId(),
                piece.getName(),
                types,
                piece.getOwner() == null ? null : piece.getOwner().getId(),
                piece.getLocation() == null ? null : piece.getLocation().getId(),
                piece.getStatus(),
                piece.getCreatedAt(),
                piece.getUpdatedAt()
        );
    }

    /**
     * @param piece the piece projecting into the DTO
     * @param sortedTypes pre-sorted, deduplicated list of types — pass when the caller already
     *                    has them in hand to avoid re-sorting
     * @return DTO with the supplied types
     */
    public static PieceListItemDto from(Piece piece, List<PieceType> sortedTypes) {
        List<PieceTypeRefDto> refs = sortedTypes.stream().map(PieceTypeRefDto::from).toList();
        return new PieceListItemDto(
                piece.getId(),
                piece.getName(),
                refs,
                piece.getOwner() == null ? null : piece.getOwner().getId(),
                piece.getLocation() == null ? null : piece.getLocation().getId(),
                piece.getStatus(),
                piece.getCreatedAt(),
                piece.getUpdatedAt()
        );
    }
}
