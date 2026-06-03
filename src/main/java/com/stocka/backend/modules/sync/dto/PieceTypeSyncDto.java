package com.stocka.backend.modules.sync.dto;

import java.time.LocalDateTime;

/**
 * A {@code pieceTypes} change in the sync pull feed. A non-null {@link #deletedAt()} is a tombstone.
 *
 * @param syncId    client-stable identity
 * @param rev       per-organization change-sequence cursor
 * @param name      type name
 * @param createdAt creation timestamp
 * @param updatedAt last update timestamp
 * @param deletedAt soft-delete timestamp, or {@code null} when live
 * @since 0.2.0
 */
public record PieceTypeSyncDto(
        String syncId,
        long rev,
        String name,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime deletedAt
) {
}
