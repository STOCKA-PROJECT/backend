package com.stocka.backend.modules.sync.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.stocka.backend.modules.sync.dto.LocationSyncDto;
import com.stocka.backend.modules.sync.dto.SyncChangesResponse;
import com.stocka.backend.modules.sync.repository.SyncReadRepository;
import com.stocka.backend.modules.sync.repository.SyncReadRepository.LocationSyncRow;

/**
 * Orchestrates the offline sync pull feed: returns the documents of an organization that changed
 * since the client's per-collection checkpoint, ordered by {@code rev}, tombstones included.
 *
 * @since 0.2.0
 */
@Service
public class SyncService {

    /** Minimum desktop client schema version accepted by this server (DECISIONS-AND-RISKS R23). */
    public static final int MIN_CLIENT_VERSION = 1;

    /** Default and hard cap on the number of rows returned per collection in one pull. */
    public static final int DEFAULT_LIMIT = 500;
    public static final int MAX_LIMIT = 1000;

    static final String COLLECTION_LOCATIONS = "locations";

    private final SyncReadRepository syncReadRepository;

    public SyncService(SyncReadRepository syncReadRepository) {
        this.syncReadRepository = syncReadRepository;
    }

    /**
     * Pulls the next page of changes for an organization.
     *
     * @param orgId      organization id
     * @param checkpoint per-collection lower bound on {@code rev} (missing entries default to 0)
     * @param limit      requested page size (clamped to [1, {@value #MAX_LIMIT}])
     * @return the changed documents, the advanced checkpoint and whether more remain
     */
    @Transactional(readOnly = true)
    public SyncChangesResponse pull(Integer orgId, Map<String, Long> checkpoint, int limit) {
        int pageSize = clampLimit(limit);
        long locationsSince = checkpoint.getOrDefault(COLLECTION_LOCATIONS, 0L);

        List<LocationSyncRow> rows = syncReadRepository.findChangedLocations(orgId, locationsSince, pageSize);
        List<LocationSyncDto> locations = rows.stream().map(SyncService::toLocationDto).toList();

        Map<String, Long> newCheckpoint = new HashMap<>();
        long locationsCheckpoint = locations.isEmpty()
                ? locationsSince
                : locations.get(locations.size() - 1).rev();
        newCheckpoint.put(COLLECTION_LOCATIONS, locationsCheckpoint);

        boolean hasMore = locations.size() >= pageSize;

        return new SyncChangesResponse(
                new SyncChangesResponse.Changes(locations),
                newCheckpoint,
                hasMore,
                MIN_CLIENT_VERSION);
    }

    /**
     * Parses the {@code since} query parameter ({@code "collection:rev,collection:rev"}) into a
     * per-collection checkpoint map. Blank or malformed entries are ignored (treated as 0).
     *
     * @param since raw query value, may be {@code null}
     * @return parsed checkpoint map (never {@code null})
     */
    public static Map<String, Long> parseCheckpoint(String since) {
        Map<String, Long> checkpoint = new HashMap<>();
        if (since == null || since.isBlank()) {
            return checkpoint;
        }
        for (String part : since.split(",")) {
            int sep = part.indexOf(':');
            if (sep <= 0 || sep == part.length() - 1) {
                continue;
            }
            String collection = part.substring(0, sep).trim();
            try {
                checkpoint.put(collection, Long.parseLong(part.substring(sep + 1).trim()));
            } catch (NumberFormatException ignored) {
                // Skip malformed cursor entry; the collection restarts from 0.
            }
        }
        return checkpoint;
    }

    private static int clampLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private static LocationSyncDto toLocationDto(LocationSyncRow row) {
        return new LocationSyncDto(
                row.getSyncId(),
                row.getRev(),
                row.getName(),
                row.getDescription(),
                row.getParentSyncId(),
                row.getCreatedAt(),
                row.getUpdatedAt(),
                row.getDeletedAt());
    }
}
