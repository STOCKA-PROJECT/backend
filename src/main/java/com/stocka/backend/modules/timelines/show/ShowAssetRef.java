package com.stocka.backend.modules.timelines.show;

import com.stocka.backend.modules.common.schema.Param;

/**
 * Where a file listed in {@code assets} comes from in Stocka: an attachment of a piece. Listed in
 * {@code meta.assets}, outside the hash.
 *
 * @param name         asset name used by the events ({@code <attachmentId>-<filename>})
 * @param kind         {@code audio} or {@code video}
 * @param pieceId      piece the file belongs to
 * @param attachmentId attachment id
 * @param mimeType     MIME type
 * @param sizeBytes    size in bytes
 * @param durationMs   length in milliseconds, or {@code null} when unknown
 */
public record ShowAssetRef(
        @Param("Asset name used by the events: <attachmentId>-<filename>") String name,
        @Param("audio or video") String kind,
        @Param("Piece the file belongs to") int pieceId,
        @Param("Attachment id") int attachmentId,
        @Param("MIME type") String mimeType,
        @Param("Size in bytes") long sizeBytes,
        @Param("Length in ms, null when unknown") Long durationMs) {
}
