package com.stocka.backend.modules.storage;

import java.util.Locale;
import java.util.UUID;

/**
 * Shared key-building rules for R2 implementations. Centralized so the local fallback and the
 * real R2 client write to the same paths and the file system layout in dev mirrors what
 * production sees.
 */
final class R2Keys {

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
    static String buildPieceKey(int orgId, int pieceId, String originalFilename) {
        String base = (originalFilename == null || originalFilename.isBlank())
                ? "file"
                : originalFilename.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
        return "org/" + orgId + "/piece/" + pieceId + "/" + UUID.randomUUID() + "-" + base;
    }
}
