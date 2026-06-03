package com.stocka.backend.modules.sync.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A {@code pieces} change in the sync pull feed, modelled as an aggregate: the piece's scalar
 * fields plus its embedded attribute values and its references to other syncable entities by
 * {@code syncId}. The attribute-value child tables have no own identity/soft-delete, so they
 * travel inside this document. A non-null {@link #deletedAt()} marks a tombstone.
 *
 * @param syncId                client-stable identity
 * @param rev                   per-organization change-sequence value (the pull cursor)
 * @param name                  piece name
 * @param serialNumber          optional serial number (unique within the organization)
 * @param description           optional description
 * @param status                piece status (e.g. {@code ACTIVE}, {@code PENDING})
 * @param ownerUserId           owner user id, or {@code null} (users are a read-only catalog)
 * @param locationSyncId        location sync id, or {@code null}
 * @param coverAttachmentSyncId cover attachment sync id, or {@code null}
 * @param pieceTypeSyncIds      sync ids of the piece's types (many-to-many)
 * @param typeAttributeValues   values for type-level attributes
 * @param orgAttributeValues    values for organization-level attributes
 * @param createdAt             creation timestamp
 * @param updatedAt             last update timestamp
 * @param deletedAt             soft-delete timestamp, or {@code null} when live
 * @since 0.2.0
 */
public record PieceSyncDto(
        String syncId,
        long rev,
        String name,
        String serialNumber,
        String description,
        String status,
        Integer ownerUserId,
        String locationSyncId,
        String coverAttachmentSyncId,
        List<String> pieceTypeSyncIds,
        List<AttributeValueSyncDto> typeAttributeValues,
        List<AttributeValueSyncDto> orgAttributeValues,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime deletedAt
) {
}
