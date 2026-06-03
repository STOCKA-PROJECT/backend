package com.stocka.backend.modules.sync.dto;

import java.util.List;
import java.util.Map;

/**
 * Response of the sync pull endpoint. Groups changed documents by collection, returns the new
 * per-collection {@code checkpoint} (max {@code rev} the client may store) and a {@code hasMore}
 * flag telling the client to pull again with the new checkpoint.
 *
 * <p>{@code minClientVersion} lets the server force outdated desktop clients to upgrade
 * (DECISIONS-AND-RISKS R23). The {@code changes} map is keyed by collection name; only the
 * collections implemented so far are populated.
 *
 * @param changes          changed documents per collection (e.g. {@code "locations"})
 * @param checkpoint       new max {@code rev} per collection
 * @param hasMore          {@code true} when more changes remain beyond this page
 * @param minClientVersion minimum desktop client schema version the server accepts
 * @since 0.2.0
 */
public record SyncChangesResponse(
        Changes changes,
        Map<String, Long> checkpoint,
        boolean hasMore,
        int minClientVersion
) {

    /**
     * Per-collection change lists (each including tombstones), keyed by collection in the
     * dependency order clients should apply them.
     *
     * @param pieceTypes          changed piece types
     * @param pieceTypeAttributes changed type-level attribute definitions
     * @param locations           changed locations
     * @param orgAttributes       changed organization-level attribute definitions
     * @param pieces              changed pieces (aggregates)
     * @param attachments         changed attachment metadata
     */
    public record Changes(
            List<PieceTypeSyncDto> pieceTypes,
            List<PieceTypeAttributeSyncDto> pieceTypeAttributes,
            List<LocationSyncDto> locations,
            List<OrgAttributeSyncDto> orgAttributes,
            List<PieceSyncDto> pieces,
            List<AttachmentSyncDto> attachments
    ) {
    }
}
