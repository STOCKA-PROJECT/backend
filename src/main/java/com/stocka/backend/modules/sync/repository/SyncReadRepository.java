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
     * Loads a single location by sync id <strong>including soft-deleted rows</strong> (native query
     * bypasses {@code @SQLRestriction}). Used by the push handler to detect existence, the current
     * {@code rev} and tombstone state, and to build the canonical {@code serverDoc}.
     *
     * @param syncId client-stable sync id
     * @return the row, or {@code null} when no such location exists
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
            WHERE l.sync_id = :syncId
            """, nativeQuery = true)
    LocationSyncRow findLocationBySyncId(@Param("syncId") String syncId);

    /**
     * Loads a single piece type by sync id <strong>including soft-deleted rows</strong> (used by the
     * push handler for existence/rev/tombstone detection and to build the {@code serverDoc}).
     *
     * @param syncId client-stable sync id
     * @return the row, or {@code null} when no such piece type exists
     */
    @Query(value = """
            SELECT sync_id AS syncId, rev AS rev, name AS name,
                   created_at AS createdAt, updated_at AS updatedAt, deleted_at AS deletedAt
            FROM piece_types
            WHERE sync_id = :syncId
            """, nativeQuery = true)
    PieceTypeSyncRow findPieceTypeBySyncId(@Param("syncId") String syncId);

    /**
     * Loads a single organization attribute by sync id <strong>including soft-deleted rows</strong>.
     *
     * @param syncId client-stable sync id
     * @return the row, or {@code null} when no such attribute exists
     */
    @Query(value = """
            SELECT sync_id AS syncId, rev AS rev, name AS name, display_name AS displayName,
                   type AS attrType, is_required AS isRequired, position AS attrPosition,
                   validators_json AS validatorsJson,
                   created_at AS createdAt, updated_at AS updatedAt, deleted_at AS deletedAt
            FROM organization_piece_attributes
            WHERE sync_id = :syncId
            """, nativeQuery = true)
    OrgAttributeSyncRow findOrgAttributeBySyncId(@Param("syncId") String syncId);

    /**
     * Loads a single type attribute by sync id <strong>including soft-deleted rows</strong>, with
     * its owning type exposed by {@code syncId}.
     *
     * @param syncId client-stable sync id
     * @return the row, or {@code null} when no such attribute exists
     */
    @Query(value = """
            SELECT a.sync_id AS syncId, a.rev AS rev, pt.sync_id AS pieceTypeSyncId,
                   a.name AS name, a.display_name AS displayName, a.type AS attrType,
                   a.is_required AS isRequired, a.position AS attrPosition,
                   a.validators_json AS validatorsJson,
                   a.created_at AS createdAt, a.updated_at AS updatedAt, a.deleted_at AS deletedAt
            FROM piece_type_attributes a
            JOIN piece_types pt ON pt.id = a.piece_type_id
            WHERE a.sync_id = :syncId
            """, nativeQuery = true)
    PieceTypeAttributeSyncRow findPieceTypeAttributeBySyncId(@Param("syncId") String syncId);

    /**
     * Returns the pieces of an organization whose {@code rev} is greater than the checkpoint,
     * ordered by {@code rev}, including tombstones. Location and cover attachment are exposed by
     * their {@code sync_id} via left joins; the owner is exposed by user id (users are a read-only
     * catalog). Attribute values and type associations are fetched separately and assembled into
     * the aggregate by the service.
     *
     * @param orgId organization id
     * @param since exclusive lower bound on {@code rev}
     * @param limit maximum rows to return
     * @return changed piece rows ordered by {@code rev}
     */
    @Query(value = """
            SELECT p.id              AS id,
                   p.sync_id         AS syncId,
                   p.rev             AS rev,
                   p.name            AS name,
                   p.serial_number   AS serialNumber,
                   p.description     AS description,
                   p.status          AS status,
                   p.owner_user_id   AS ownerUserId,
                   loc.sync_id       AS locationSyncId,
                   ca.sync_id        AS coverAttachmentSyncId,
                   p.created_at      AS createdAt,
                   p.updated_at      AS updatedAt,
                   p.deleted_at      AS deletedAt
            FROM pieces p
            LEFT JOIN locations loc ON loc.id = p.location_id
            LEFT JOIN piece_attachments ca ON ca.id = p.cover_attachment_id
            WHERE p.organization_id = :orgId
              AND p.rev > :since
            ORDER BY p.rev ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<PieceSyncRow> findChangedPieces(
            @Param("orgId") Integer orgId,
            @Param("since") long since,
            @Param("limit") int limit);

    /**
     * Returns the {@code (pieceId, typeSyncId)} many-to-many associations for the given pieces.
     *
     * @param pieceIds piece numeric ids
     * @return type reference rows
     */
    @Query(value = """
            SELECT ppt.piece_id AS pieceId, pt.sync_id AS typeSyncId
            FROM piece_piece_types ppt
            JOIN piece_types pt ON pt.id = ppt.piece_type_id
            WHERE ppt.piece_id IN (:pieceIds)
            """, nativeQuery = true)
    List<PieceTypeRefRow> findPieceTypeRefs(@Param("pieceIds") List<Integer> pieceIds);

    /**
     * Returns the type-level attribute values of the given pieces, attribute referenced by syncId.
     *
     * @param pieceIds piece numeric ids
     * @return attribute value rows
     */
    @Query(value = """
            SELECT pav.piece_id AS pieceId, pta.sync_id AS attributeSyncId, pav.attribute_value AS attrValue
            FROM piece_attribute_values pav
            JOIN piece_type_attributes pta ON pta.id = pav.piece_type_attribute_id
            WHERE pav.piece_id IN (:pieceIds)
            """, nativeQuery = true)
    List<AttributeValueRow> findTypeAttributeValues(@Param("pieceIds") List<Integer> pieceIds);

    /**
     * Returns the organization-level attribute values of the given pieces, attribute by syncId.
     *
     * @param pieceIds piece numeric ids
     * @return attribute value rows
     */
    @Query(value = """
            SELECT poav.piece_id AS pieceId, opa.sync_id AS attributeSyncId,
                   poav.attribute_value AS attrValue
            FROM piece_organization_attribute_values poav
            JOIN organization_piece_attributes opa ON opa.id = poav.organization_attribute_id
            WHERE poav.piece_id IN (:pieceIds)
            """, nativeQuery = true)
    List<AttributeValueRow> findOrgAttributeValues(@Param("pieceIds") List<Integer> pieceIds);

    /**
     * Returns the piece types of an organization changed since the checkpoint, tombstones included.
     *
     * @param orgId organization id
     * @param since exclusive lower bound on {@code rev}
     * @param limit maximum rows
     * @return changed piece type rows ordered by {@code rev}
     */
    @Query(value = """
            SELECT sync_id AS syncId, rev AS rev, name AS name,
                   created_at AS createdAt, updated_at AS updatedAt, deleted_at AS deletedAt
            FROM piece_types
            WHERE organization_id = :orgId AND rev > :since
            ORDER BY rev ASC LIMIT :limit
            """, nativeQuery = true)
    List<PieceTypeSyncRow> findChangedPieceTypes(
            @Param("orgId") Integer orgId, @Param("since") long since, @Param("limit") int limit);

    /**
     * Returns the type-level attribute definitions of an organization changed since the checkpoint,
     * tombstones included, with the owning type exposed by {@code syncId}.
     *
     * @param orgId organization id
     * @param since exclusive lower bound on {@code rev}
     * @param limit maximum rows
     * @return changed attribute rows ordered by {@code rev}
     */
    @Query(value = """
            SELECT a.sync_id AS syncId, a.rev AS rev, pt.sync_id AS pieceTypeSyncId,
                   a.name AS name, a.display_name AS displayName, a.type AS attrType,
                   a.is_required AS isRequired, a.position AS attrPosition,
                   a.validators_json AS validatorsJson,
                   a.created_at AS createdAt, a.updated_at AS updatedAt, a.deleted_at AS deletedAt
            FROM piece_type_attributes a
            JOIN piece_types pt ON pt.id = a.piece_type_id
            WHERE pt.organization_id = :orgId AND a.rev > :since
            ORDER BY a.rev ASC LIMIT :limit
            """, nativeQuery = true)
    List<PieceTypeAttributeSyncRow> findChangedPieceTypeAttributes(
            @Param("orgId") Integer orgId, @Param("since") long since, @Param("limit") int limit);

    /**
     * Returns the organization-level attribute definitions changed since the checkpoint, tombstones
     * included.
     *
     * @param orgId organization id
     * @param since exclusive lower bound on {@code rev}
     * @param limit maximum rows
     * @return changed attribute rows ordered by {@code rev}
     */
    @Query(value = """
            SELECT sync_id AS syncId, rev AS rev, name AS name, display_name AS displayName,
                   type AS attrType, is_required AS isRequired, position AS attrPosition,
                   validators_json AS validatorsJson,
                   created_at AS createdAt, updated_at AS updatedAt, deleted_at AS deletedAt
            FROM organization_piece_attributes
            WHERE organization_id = :orgId AND rev > :since
            ORDER BY rev ASC LIMIT :limit
            """, nativeQuery = true)
    List<OrgAttributeSyncRow> findChangedOrgAttributes(
            @Param("orgId") Integer orgId, @Param("since") long since, @Param("limit") int limit);

    /**
     * Returns the attachment metadata of an organization changed since the checkpoint, tombstones
     * included, with the owning piece exposed by {@code syncId}.
     *
     * @param orgId organization id
     * @param since exclusive lower bound on {@code rev}
     * @param limit maximum rows
     * @return changed attachment rows ordered by {@code rev}
     */
    @Query(value = """
            SELECT a.sync_id AS syncId, a.rev AS rev, p.sync_id AS pieceSyncId, a.kind AS kind,
                   a.original_filename AS originalFilename, a.mime_type AS mimeType,
                   a.size_bytes AS sizeBytes, a.r2_key AS r2Key,
                   a.created_at AS createdAt, a.deleted_at AS deletedAt
            FROM piece_attachments a
            JOIN pieces p ON p.id = a.piece_id
            WHERE p.organization_id = :orgId AND a.rev > :since
            ORDER BY a.rev ASC LIMIT :limit
            """, nativeQuery = true)
    List<AttachmentSyncRow> findChangedAttachments(
            @Param("orgId") Integer orgId, @Param("since") long since, @Param("limit") int limit);

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

    /**
     * Projection over a changed piece row. {@code getId()} is the numeric id (internal, used to
     * fetch the aggregate children); it is not exposed to clients.
     *
     * @since 0.2.0
     */
    interface PieceSyncRow {
        Integer getId();

        String getSyncId();

        long getRev();

        String getName();

        String getSerialNumber();

        String getDescription();

        String getStatus();

        Integer getOwnerUserId();

        String getLocationSyncId();

        String getCoverAttachmentSyncId();

        LocalDateTime getCreatedAt();

        LocalDateTime getUpdatedAt();

        LocalDateTime getDeletedAt();
    }

    /**
     * Projection over a {@code (pieceId, typeSyncId)} association row.
     *
     * @since 0.2.0
     */
    interface PieceTypeRefRow {
        Integer getPieceId();

        String getTypeSyncId();
    }

    /**
     * Projection over an attribute value row, attribute referenced by its sync id.
     *
     * @since 0.2.0
     */
    interface AttributeValueRow {
        Integer getPieceId();

        String getAttributeSyncId();

        String getAttrValue();
    }

    /**
     * Projection over a changed piece type row.
     *
     * @since 0.2.0
     */
    interface PieceTypeSyncRow {
        String getSyncId();

        long getRev();

        String getName();

        LocalDateTime getCreatedAt();

        LocalDateTime getUpdatedAt();

        LocalDateTime getDeletedAt();
    }

    /**
     * Projection over a changed type-level attribute definition row.
     *
     * @since 0.2.0
     */
    interface PieceTypeAttributeSyncRow {
        String getSyncId();

        long getRev();

        String getPieceTypeSyncId();

        String getName();

        String getDisplayName();

        String getAttrType();

        boolean getIsRequired();

        int getAttrPosition();

        String getValidatorsJson();

        LocalDateTime getCreatedAt();

        LocalDateTime getUpdatedAt();

        LocalDateTime getDeletedAt();
    }

    /**
     * Projection over a changed organization-level attribute definition row.
     *
     * @since 0.2.0
     */
    interface OrgAttributeSyncRow {
        String getSyncId();

        long getRev();

        String getName();

        String getDisplayName();

        String getAttrType();

        boolean getIsRequired();

        int getAttrPosition();

        String getValidatorsJson();

        LocalDateTime getCreatedAt();

        LocalDateTime getUpdatedAt();

        LocalDateTime getDeletedAt();
    }

    /**
     * Projection over a changed attachment metadata row.
     *
     * @since 0.2.0
     */
    interface AttachmentSyncRow {
        String getSyncId();

        long getRev();

        String getPieceSyncId();

        String getKind();

        String getOriginalFilename();

        String getMimeType();

        long getSizeBytes();

        String getR2Key();

        LocalDateTime getCreatedAt();

        LocalDateTime getDeletedAt();
    }
}
