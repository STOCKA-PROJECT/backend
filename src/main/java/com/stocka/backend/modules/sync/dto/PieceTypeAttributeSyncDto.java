package com.stocka.backend.modules.sync.dto;

import java.time.LocalDateTime;

/**
 * A {@code pieceTypeAttributes} change in the sync pull feed. References its owning type by
 * {@code syncId}. A non-null {@link #deletedAt()} is a tombstone.
 *
 * @param syncId         client-stable identity
 * @param rev            per-organization change-sequence cursor
 * @param pieceTypeSyncId owning piece type sync id
 * @param name           technical name
 * @param displayName    user-facing label
 * @param type           attribute type (enum name)
 * @param required       whether the attribute is required
 * @param position       display order
 * @param validatorsJson type-specific validator rules (JSON), or {@code null}
 * @param createdAt      creation timestamp
 * @param updatedAt      last update timestamp
 * @param deletedAt      soft-delete timestamp, or {@code null} when live
 * @since 0.2.0
 */
public record PieceTypeAttributeSyncDto(
        String syncId,
        long rev,
        String pieceTypeSyncId,
        String name,
        String displayName,
        String type,
        boolean required,
        int position,
        String validatorsJson,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime deletedAt
) {
}
