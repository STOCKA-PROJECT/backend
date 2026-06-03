package com.stocka.backend.modules.sync.dto;

import java.time.LocalDateTime;

/**
 * A {@code attachmentsMeta} change in the sync pull feed. Carries attachment metadata only; the
 * binary lives in R2 and is fetched on demand. References its piece by {@code syncId}. A non-null
 * {@link #deletedAt()} is a tombstone.
 *
 * @param syncId           client-stable identity
 * @param rev              per-organization change-sequence cursor
 * @param pieceSyncId      owning piece sync id
 * @param kind             {@code IMAGE} or {@code DOCUMENT}
 * @param originalFilename original file name
 * @param mimeType         MIME type
 * @param sizeBytes        size in bytes
 * @param r2Key            object key in R2
 * @param createdAt        creation timestamp
 * @param deletedAt        soft-delete timestamp, or {@code null} when live
 * @since 0.2.0
 */
public record AttachmentSyncDto(
        String syncId,
        long rev,
        String pieceSyncId,
        String kind,
        String originalFilename,
        String mimeType,
        long sizeBytes,
        String r2Key,
        LocalDateTime createdAt,
        LocalDateTime deletedAt
) {
}
