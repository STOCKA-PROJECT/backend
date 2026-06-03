package com.stocka.backend.modules.sync.dto;

import java.time.LocalDateTime;

/**
 * An {@code orgAttributes} change in the sync pull feed (organization-level piece attribute).
 * A non-null {@link #deletedAt()} is a tombstone.
 *
 * @param syncId         client-stable identity
 * @param rev            per-organization change-sequence cursor
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
public record OrgAttributeSyncDto(
        String syncId,
        long rev,
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
