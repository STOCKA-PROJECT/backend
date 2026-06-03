package com.stocka.backend.modules.sync.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.stocka.backend.modules.locations.entity.Location;

/**
 * Read-only access for the offline sync pull feed. Uses native queries on purpose so that the
 * entity-level {@code @SQLRestriction("deleted_at IS NULL")} is <strong>not</strong> applied:
 * the pull must return soft-deleted rows as tombstones, otherwise offline clients would never
 * learn about deletions (DECISIONS-AND-RISKS R1).
 *
 * @since 0.2.0
 */
public interface SyncReadRepository extends Repository<Location, Integer> {

    /**
     * Returns the locations of an organization whose {@code rev} is greater than the client's
     * checkpoint, ordered by {@code rev} ascending, including tombstones. The parent is exposed by
     * its {@code sync_id} via a self-join.
     *
     * @param orgId organization id
     * @param since exclusive lower bound on {@code rev} (the client checkpoint; {@code 0} for full)
     * @param limit maximum rows to return
     * @return changed location rows ordered by {@code rev}
     */
    @Query(value = """
            SELECT l.sync_id      AS syncId,
                   l.rev          AS rev,
                   l.name         AS name,
                   l.description  AS description,
                   p.sync_id      AS parentSyncId,
                   l.created_at   AS createdAt,
                   l.updated_at   AS updatedAt,
                   l.deleted_at   AS deletedAt
            FROM locations l
            LEFT JOIN locations p ON p.id = l.parent_id
            WHERE l.organization_id = :orgId
              AND l.rev > :since
            ORDER BY l.rev ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<LocationSyncRow> findChangedLocations(
            @Param("orgId") Integer orgId,
            @Param("since") long since,
            @Param("limit") int limit);

    /**
     * Projection over a changed location row. Field names match the native query aliases.
     *
     * @since 0.2.0
     */
    interface LocationSyncRow {
        String getSyncId();

        long getRev();

        String getName();

        String getDescription();

        String getParentSyncId();

        LocalDateTime getCreatedAt();

        LocalDateTime getUpdatedAt();

        LocalDateTime getDeletedAt();
    }
}
