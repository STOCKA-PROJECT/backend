package com.stocka.backend.modules.sync.dto;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Batch of offline mutations pushed by the desktop client, ordered by the client to respect
 * dependencies (e.g. a location before the piece that references it).
 *
 * @param mutations the ordered mutations to apply
 * @since 0.2.0
 */
public record SyncMutationRequest(List<Item> mutations) {

    /**
     * A single mutation.
     *
     * @param mutationId client-generated idempotency key (UUID)
     * @param collection target collection (e.g. {@code "locations"})
     * @param op         {@code "upsert"} or {@code "delete"}
     * @param syncId     stable identity of the affected document
     * @param baseRev    rev the client edited on top of ({@code null} for a fresh create)
     * @param doc        document payload for {@code upsert} (collection-specific fields); ignored
     *                   for {@code delete}
     */
    public record Item(
            String mutationId,
            String collection,
            String op,
            String syncId,
            Long baseRev,
            JsonNode doc
    ) {
    }
}
