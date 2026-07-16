package com.stocka.backend.modules.pieces.dto;

import java.util.Date;
import java.util.List;

import com.stocka.backend.modules.pieces.entity.Piece;
import com.stocka.backend.modules.pieces.entity.PieceStatus;
import com.stocka.backend.modules.piecetypes.entity.PieceType;

/**
 * Compact view of a piece for paginated listings. {@code ownerUserId} / {@code ownerContactId}
 * are the raw owner ids (at most one non-null) and {@code owner} is the embedded display summary.
 */
public record PieceListItemDto(
        Integer id,
        String name,
        String serialNumber,
        List<PieceTypeRefDto> pieceTypes,
        Integer ownerUserId,
        Integer ownerContactId,
        PieceOwnerSummaryDto owner,
        Integer locationId,
        Integer coverAttachmentId,
        PieceStatus status,
        Date createdAt,
        Date updatedAt
) {
    public static PieceListItemDto from(Piece piece) {
        List<PieceTypeRefDto> types = piece.getPieceTypes().stream()
                .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                .map(PieceTypeRefDto::from)
                .toList();
        return build(piece, types);
    }

    /**
     * @param piece the piece projecting into the DTO
     * @param sortedTypes pre-sorted, deduplicated list of types — pass when the caller already
     *                    has them in hand to avoid re-sorting
     * @return DTO with the supplied types
     */
    public static PieceListItemDto from(Piece piece, List<PieceType> sortedTypes) {
        List<PieceTypeRefDto> refs = sortedTypes.stream().map(PieceTypeRefDto::from).toList();
        return build(piece, refs);
    }

    private static PieceListItemDto build(Piece piece, List<PieceTypeRefDto> types) {
        return new PieceListItemDto(
                piece.getId(),
                piece.getName(),
                piece.getSerialNumber(),
                types,
                piece.getOwner() == null ? null : piece.getOwner().getId(),
                piece.getOwnerContact() == null ? null : piece.getOwnerContact().getId(),
                PieceOwnerSummaryDto.from(piece),
                piece.getLocation() == null ? null : piece.getLocation().getId(),
                piece.getCoverAttachment() == null ? null : piece.getCoverAttachment().getId(),
                piece.getStatus(),
                piece.getCreatedAt(),
                piece.getUpdatedAt()
        );
    }
}
