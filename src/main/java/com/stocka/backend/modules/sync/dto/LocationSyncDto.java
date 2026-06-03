package com.stocka.backend.modules.sync.dto;

import java.time.LocalDateTime;

/**
 * A single {@code locations} change in the sync pull feed. References its parent by {@code syncId}
 * (never by numeric id) so offline clients can resolve the tree without server ids. A non-null
 * {@link #deletedAt()} marks a tombstone the client must apply as a local delete.
 *
 * @param syncId       client-stable identity
 * @param rev          per-organization change-sequence value (the pull cursor)
 * @param name         location name
 * @param description  optional description
 * @param parentSyncId parent location sync id, or {@code null} when at the tree root
 * @param createdAt    creation timestamp
 * @param updatedAt    last update timestamp
 * @param deletedAt    soft-delete timestamp, or {@code null} when live
 * @since 0.2.0
 */
public record LocationSyncDto(
        String syncId,
        long rev,
        String name,
        String description,
        String parentSyncId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime deletedAt
) {
}
