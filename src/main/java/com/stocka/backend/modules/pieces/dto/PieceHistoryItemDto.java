package com.stocka.backend.modules.pieces.dto;

import java.util.Date;

import com.stocka.backend.modules.pieces.entity.PieceHistory;
import com.stocka.backend.modules.pieces.entity.PieceHistoryAction;

/** One row in {@code GET /pieces/{id}/history}. */
public record PieceHistoryItemDto(
        Long id,
        Integer actorUserId,
        PieceHistoryAction action,
        String fieldName,
        String oldValue,
        String newValue,
        Date createdAt
) {
    public static PieceHistoryItemDto from(PieceHistory entry) {
        return new PieceHistoryItemDto(
                entry.getId(),
                entry.getActor() == null ? null : entry.getActor().getId(),
                entry.getAction(),
                entry.getFieldName(),
                entry.getOldValue(),
                entry.getNewValue(),
                entry.getCreatedAt()
        );
    }
}
