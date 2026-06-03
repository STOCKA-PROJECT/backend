package com.stocka.backend.modules.sync.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToLongFunction;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.stocka.backend.modules.sync.dto.AttachmentSyncDto;
import com.stocka.backend.modules.sync.dto.AttributeValueSyncDto;
import com.stocka.backend.modules.sync.dto.LocationSyncDto;
import com.stocka.backend.modules.sync.dto.OrgAttributeSyncDto;
import com.stocka.backend.modules.sync.dto.PieceSyncDto;
import com.stocka.backend.modules.sync.dto.PieceTypeAttributeSyncDto;
import com.stocka.backend.modules.sync.dto.PieceTypeSyncDto;
import com.stocka.backend.modules.sync.dto.SyncChangesResponse;
import com.stocka.backend.modules.sync.repository.SyncReadRepository;
import com.stocka.backend.modules.sync.repository.SyncReadRepository.AttachmentSyncRow;
import com.stocka.backend.modules.sync.repository.SyncReadRepository.AttributeValueRow;
import com.stocka.backend.modules.sync.repository.SyncReadRepository.LocationSyncRow;
import com.stocka.backend.modules.sync.repository.SyncReadRepository.OrgAttributeSyncRow;
import com.stocka.backend.modules.sync.repository.SyncReadRepository.PieceSyncRow;
import com.stocka.backend.modules.sync.repository.SyncReadRepository.PieceTypeAttributeSyncRow;
import com.stocka.backend.modules.sync.repository.SyncReadRepository.PieceTypeRefRow;
import com.stocka.backend.modules.sync.repository.SyncReadRepository.PieceTypeSyncRow;

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

    static final String COLLECTION_PIECE_TYPES = "pieceTypes";
    static final String COLLECTION_PIECE_TYPE_ATTRIBUTES = "pieceTypeAttributes";
    static final String COLLECTION_LOCATIONS = "locations";
    static final String COLLECTION_ORG_ATTRIBUTES = "orgAttributes";
    static final String COLLECTION_PIECES = "pieces";
    static final String COLLECTION_ATTACHMENTS = "attachments";

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

        long pieceTypesSince = checkpoint.getOrDefault(COLLECTION_PIECE_TYPES, 0L);
        List<PieceTypeSyncDto> pieceTypes =
                syncReadRepository.findChangedPieceTypes(orgId, pieceTypesSince, pageSize)
                        .stream().map(SyncService::toPieceTypeDto).toList();

        long ptAttrsSince = checkpoint.getOrDefault(COLLECTION_PIECE_TYPE_ATTRIBUTES, 0L);
        List<PieceTypeAttributeSyncDto> pieceTypeAttributes =
                syncReadRepository.findChangedPieceTypeAttributes(orgId, ptAttrsSince, pageSize)
                        .stream().map(SyncService::toPieceTypeAttributeDto).toList();

        long locationsSince = checkpoint.getOrDefault(COLLECTION_LOCATIONS, 0L);
        List<LocationSyncDto> locations =
                syncReadRepository.findChangedLocations(orgId, locationsSince, pageSize)
                        .stream().map(SyncService::toLocationDto).toList();

        long orgAttrsSince = checkpoint.getOrDefault(COLLECTION_ORG_ATTRIBUTES, 0L);
        List<OrgAttributeSyncDto> orgAttributes =
                syncReadRepository.findChangedOrgAttributes(orgId, orgAttrsSince, pageSize)
                        .stream().map(SyncService::toOrgAttributeDto).toList();

        long piecesSince = checkpoint.getOrDefault(COLLECTION_PIECES, 0L);
        List<PieceSyncDto> pieces =
                assemblePieces(syncReadRepository.findChangedPieces(orgId, piecesSince, pageSize));

        long attachmentsSince = checkpoint.getOrDefault(COLLECTION_ATTACHMENTS, 0L);
        List<AttachmentSyncDto> attachments =
                syncReadRepository.findChangedAttachments(orgId, attachmentsSince, pageSize)
                        .stream().map(SyncService::toAttachmentDto).toList();

        Map<String, Long> newCheckpoint = new HashMap<>();
        newCheckpoint.put(COLLECTION_PIECE_TYPES, advance(pieceTypes, pieceTypesSince, PieceTypeSyncDto::rev));
        newCheckpoint.put(COLLECTION_PIECE_TYPE_ATTRIBUTES,
                advance(pieceTypeAttributes, ptAttrsSince, PieceTypeAttributeSyncDto::rev));
        newCheckpoint.put(COLLECTION_LOCATIONS, advance(locations, locationsSince, LocationSyncDto::rev));
        newCheckpoint.put(COLLECTION_ORG_ATTRIBUTES,
                advance(orgAttributes, orgAttrsSince, OrgAttributeSyncDto::rev));
        newCheckpoint.put(COLLECTION_PIECES, advance(pieces, piecesSince, PieceSyncDto::rev));
        newCheckpoint.put(COLLECTION_ATTACHMENTS,
                advance(attachments, attachmentsSince, AttachmentSyncDto::rev));

        boolean hasMore = pieceTypes.size() >= pageSize
                || pieceTypeAttributes.size() >= pageSize
                || locations.size() >= pageSize
                || orgAttributes.size() >= pageSize
                || pieces.size() >= pageSize
                || attachments.size() >= pageSize;

        return new SyncChangesResponse(
                new SyncChangesResponse.Changes(
                        pieceTypes, pieceTypeAttributes, locations, orgAttributes, pieces, attachments),
                newCheckpoint,
                hasMore,
                MIN_CLIENT_VERSION);
    }

    private static <T> long advance(List<T> page, long since, ToLongFunction<T> revOf) {
        return page.isEmpty() ? since : revOf.applyAsLong(page.get(page.size() - 1));
    }

    private List<PieceSyncDto> assemblePieces(List<PieceSyncRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Integer> ids = rows.stream().map(PieceSyncRow::getId).toList();
        Map<Integer, List<String>> typeSyncIds = groupTypeRefs(syncReadRepository.findPieceTypeRefs(ids));
        Map<Integer, List<AttributeValueSyncDto>> typeValues =
                groupValues(syncReadRepository.findTypeAttributeValues(ids));
        Map<Integer, List<AttributeValueSyncDto>> orgValues =
                groupValues(syncReadRepository.findOrgAttributeValues(ids));

        List<PieceSyncDto> out = new ArrayList<>(rows.size());
        for (PieceSyncRow r : rows) {
            out.add(new PieceSyncDto(
                    r.getSyncId(),
                    r.getRev(),
                    r.getName(),
                    r.getSerialNumber(),
                    r.getDescription(),
                    r.getStatus(),
                    r.getOwnerUserId(),
                    r.getLocationSyncId(),
                    r.getCoverAttachmentSyncId(),
                    typeSyncIds.getOrDefault(r.getId(), List.of()),
                    typeValues.getOrDefault(r.getId(), List.of()),
                    orgValues.getOrDefault(r.getId(), List.of()),
                    r.getCreatedAt(),
                    r.getUpdatedAt(),
                    r.getDeletedAt()));
        }
        return out;
    }

    private static Map<Integer, List<String>> groupTypeRefs(List<PieceTypeRefRow> rows) {
        Map<Integer, List<String>> map = new HashMap<>();
        for (PieceTypeRefRow r : rows) {
            map.computeIfAbsent(r.getPieceId(), k -> new ArrayList<>()).add(r.getTypeSyncId());
        }
        return map;
    }

    private static Map<Integer, List<AttributeValueSyncDto>> groupValues(List<AttributeValueRow> rows) {
        Map<Integer, List<AttributeValueSyncDto>> map = new HashMap<>();
        for (AttributeValueRow r : rows) {
            map.computeIfAbsent(r.getPieceId(), k -> new ArrayList<>())
                    .add(new AttributeValueSyncDto(r.getAttributeSyncId(), r.getAttrValue()));
        }
        return map;
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

    private static PieceTypeSyncDto toPieceTypeDto(PieceTypeSyncRow row) {
        return new PieceTypeSyncDto(
                row.getSyncId(),
                row.getRev(),
                row.getName(),
                row.getCreatedAt(),
                row.getUpdatedAt(),
                row.getDeletedAt());
    }

    private static PieceTypeAttributeSyncDto toPieceTypeAttributeDto(PieceTypeAttributeSyncRow row) {
        return new PieceTypeAttributeSyncDto(
                row.getSyncId(),
                row.getRev(),
                row.getPieceTypeSyncId(),
                row.getName(),
                row.getDisplayName(),
                row.getAttrType(),
                row.getIsRequired(),
                row.getAttrPosition(),
                row.getValidatorsJson(),
                row.getCreatedAt(),
                row.getUpdatedAt(),
                row.getDeletedAt());
    }

    private static OrgAttributeSyncDto toOrgAttributeDto(OrgAttributeSyncRow row) {
        return new OrgAttributeSyncDto(
                row.getSyncId(),
                row.getRev(),
                row.getName(),
                row.getDisplayName(),
                row.getAttrType(),
                row.getIsRequired(),
                row.getAttrPosition(),
                row.getValidatorsJson(),
                row.getCreatedAt(),
                row.getUpdatedAt(),
                row.getDeletedAt());
    }

    private static AttachmentSyncDto toAttachmentDto(AttachmentSyncRow row) {
        return new AttachmentSyncDto(
                row.getSyncId(),
                row.getRev(),
                row.getPieceSyncId(),
                row.getKind(),
                row.getOriginalFilename(),
                row.getMimeType(),
                row.getSizeBytes(),
                row.getR2Key(),
                row.getCreatedAt(),
                row.getDeletedAt());
    }
}
