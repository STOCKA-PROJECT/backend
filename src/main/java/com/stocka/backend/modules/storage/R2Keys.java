package com.stocka.backend.modules.storage;

import java.util.Locale;
import java.util.UUID;

/**
 * Shared key-building rules for R2 implementations. Centralized so the local fallback and the
 * real R2 client write to the same paths and the file system layout in dev mirrors what
 * production sees.
 */
public final class R2Keys {

    /** Suffix appended to an original's key to name its thumbnail. */
    public static final String THUMBNAIL_SUFFIX = ".thumb.jpg";

    private R2Keys() {}

    /**
     * Builds {@code org/{orgId}/piece/{pieceId}/{uuid}-{safeFilename}}, where {@code safeFilename}
     * is the original name lowercased and stripped of any character outside
     * {@code [a-z0-9._-]}. The UUID prefix avoids collisions when two attachments share a name.
     *
     * @param orgId            organization id; must match the piece's owning organization
     * @param pieceId          piece id the attachment belongs to
     * @param originalFilename uploaded file name; may be null/blank (treated as {@code "file"})
     * @return the canonical R2 key
     */
    public static String buildPieceKey(int orgId, int pieceId, String originalFilename) {
        String base = (originalFilename == null || originalFilename.isBlank())
                ? "file"
                : originalFilename.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
        return "org/" + orgId + "/piece/" + pieceId + "/" + UUID.randomUUID() + "-" + base;
    }

    /**
     * Builds the key of the JPEG thumbnail generated for the object stored at {@code originalKey}:
     * the same key with a {@code .thumb.jpg} suffix, so the thumbnail sits next to its original in
     * the bucket and can never collide with another original (originals end with the uploaded
     * file name, and a file named {@code x.thumb.jpg} yields {@code ...x.thumb.jpg.thumb.jpg}).
     *
     * @param originalKey the key of the original object, as returned by {@link #buildPieceKey}
     * @return the thumbnail key
     */
    public static String thumbnailKeyFor(String originalKey) {
        return originalKey + THUMBNAIL_SUFFIX;
    }
}
