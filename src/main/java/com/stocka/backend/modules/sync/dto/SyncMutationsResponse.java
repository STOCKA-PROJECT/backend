package com.stocka.backend.modules.sync.dto;

import java.util.List;

/**
 * Result of a sync push batch, one {@link Result} per submitted mutation (same order).
 *
 * @param results          per-mutation outcomes
 * @param minClientVersion minimum desktop client schema version the server accepts (R23)
 * @since 0.2.0
 */
public record SyncMutationsResponse(List<Result> results, int minClientVersion) {

    /** Mutation outcome statuses. */
    public static final String STATUS_APPLIED = "applied";
    public static final String STATUS_DUPLICATE = "duplicate";
    public static final String STATUS_CONFLICT = "conflict";
    public static final String STATUS_REJECTED = "rejected";

    /**
     * Outcome of a single mutation.
     *
     * @param mutationId the client mutation id echoed back
     * @param status     one of {@code applied}, {@code duplicate}, {@code conflict}, {@code rejected}
     * @param syncId     affected document identity
     * @param serverDoc  canonical server state after applying (for applied/duplicate/conflict), or
     *                   {@code null} when rejected
     * @param errorCode  rejection/conflict reason (e.g. {@code deleted_upstream},
     *                   {@code permission_denied}); {@code null} when applied
     * @since 0.2.0
     */
    public record Result(
            String mutationId,
            String status,
            String syncId,
            Object serverDoc,
            String errorCode
    ) {
    }
}
