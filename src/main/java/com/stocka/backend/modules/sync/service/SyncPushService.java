package com.stocka.backend.modules.sync.service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.stocka.backend.modules.pieces.dto.AttributeScope;
import com.stocka.backend.modules.pieces.dto.AttributeValueInputDto;
import com.stocka.backend.modules.pieces.dto.CreatePieceDto;
import com.stocka.backend.modules.pieces.dto.UpdatePieceDto;
import com.stocka.backend.modules.pieces.entity.Piece;
import com.stocka.backend.modules.pieces.service.PieceService;
import com.stocka.backend.modules.sync.dto.PieceSyncDto;
import com.stocka.backend.modules.sync.repository.SyncReadRepository.PieceStateRow;
import com.stocka.backend.modules.locations.entity.Location;
import com.stocka.backend.modules.locations.repository.LocationRepository;
import com.stocka.backend.modules.locations.service.LocationCycleValidator;
import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.organizations.entity.OrganizationPieceAttribute;
import com.stocka.backend.modules.organizations.repository.OrganizationPieceAttributeRepository;
import com.stocka.backend.modules.organizations.security.OrganizationSecurity;
import com.stocka.backend.modules.organizations.service.OrganizationService;
import com.stocka.backend.modules.piecetypes.entity.AttributeType;
import com.stocka.backend.modules.piecetypes.entity.PieceType;
import com.stocka.backend.modules.piecetypes.entity.PieceTypeAttribute;
import com.stocka.backend.modules.piecetypes.repository.PieceTypeAttributeRepository;
import com.stocka.backend.modules.piecetypes.repository.PieceTypeRepository;
import com.stocka.backend.modules.sync.dto.LocationSyncDto;
import com.stocka.backend.modules.sync.dto.OrgAttributeSyncDto;
import com.stocka.backend.modules.sync.dto.PieceTypeAttributeSyncDto;
import com.stocka.backend.modules.sync.dto.PieceTypeSyncDto;
import com.stocka.backend.modules.sync.dto.SyncMutationRequest;
import com.stocka.backend.modules.sync.dto.SyncMutationsResponse;
import com.stocka.backend.modules.sync.dto.SyncMutationsResponse.Result;
import com.stocka.backend.modules.sync.entity.SyncMutation;
import com.stocka.backend.modules.sync.repository.SyncMutationRepository;
import com.stocka.backend.modules.sync.repository.SyncReadRepository;
import com.stocka.backend.modules.sync.repository.SyncReadRepository.LocationSyncRow;
import com.stocka.backend.modules.sync.repository.SyncReadRepository.OrgAttributeSyncRow;
import com.stocka.backend.modules.sync.repository.SyncReadRepository.PieceTypeAttributeSyncRow;
import com.stocka.backend.modules.sync.repository.SyncReadRepository.PieceTypeSyncRow;
import com.stocka.backend.modules.sync.support.SyncStamper;
import com.stocka.backend.modules.users.entity.User;

/**
 * Applies a batch of offline mutations pushed by the desktop client (the write side of sync).
 *
 * <p>Each mutation is idempotent by {@code mutationId} (a repeat returns {@code duplicate} without
 * re-applying, R24). Conflicts use last-write-wins with the server as authority; a stale write
 * (lower {@code baseRev}) still applies but is reported as {@code conflict}. Deletes are sticky:
 * an {@code upsert} over a server-side tombstone is rejected as {@code deleted_upstream} (R7).
 *
 * <p>This first iteration implements the {@code locations} collection end to end; other
 * collections are rejected as {@code unsupported_collection} until wired in.
 *
 * @since 0.2.0
 */
@Service
public class SyncPushService {

    static final String COLLECTION_LOCATIONS = "locations";
    static final String COLLECTION_PIECE_TYPES = "pieceTypes";
    static final String COLLECTION_PIECE_TYPE_ATTRIBUTES = "pieceTypeAttributes";
    static final String COLLECTION_ORG_ATTRIBUTES = "orgAttributes";
    static final String COLLECTION_PIECES = "pieces";
    static final String OP_UPSERT = "upsert";
    static final String OP_DELETE = "delete";

    static final String ERR_PERMISSION_DENIED = "permission_denied";
    static final String ERR_DELETED_UPSTREAM = "deleted_upstream";
    static final String ERR_DEPENDENCY_FAILED = "dependency_failed";
    static final String ERR_VALIDATION_FAILED = "validation_failed";
    static final String ERR_CYCLE = "cycle";
    static final String ERR_NAME_CONFLICT = "name_conflict";
    static final String ERR_SERIAL_CONFLICT = "serial_conflict";
    static final String ERR_UNSUPPORTED_COLLECTION = "unsupported_collection";
    static final String ERR_UNSUPPORTED_OP = "unsupported_op";

    private static final int MAX_TYPE_NAME_LENGTH = 120;
    private static final int MAX_ATTR_NAME_LENGTH = 80;
    private static final String SOFT_DELETE_SUFFIX = "::deleted::";

    private final SyncMutationRepository mutationRepository;
    private final SyncReadRepository syncReadRepository;
    private final LocationRepository locationRepository;
    private final PieceTypeRepository pieceTypeRepository;
    private final PieceTypeAttributeRepository pieceTypeAttributeRepository;
    private final OrganizationPieceAttributeRepository orgAttributeRepository;
    private final OrganizationService organizationService;
    private final LocationCycleValidator cycleValidator;
    private final SyncStamper syncStamper;
    private final OrganizationSecurity orgSecurity;
    private final PieceService pieceService;
    private final SyncService syncService;
    private final TransactionTemplate requiresNewTx;
    private static final Logger log = LoggerFactory.getLogger(SyncPushService.class);

    public SyncPushService(
            SyncMutationRepository mutationRepository,
            SyncReadRepository syncReadRepository,
            LocationRepository locationRepository,
            PieceTypeRepository pieceTypeRepository,
            PieceTypeAttributeRepository pieceTypeAttributeRepository,
            OrganizationPieceAttributeRepository orgAttributeRepository,
            OrganizationService organizationService,
            LocationCycleValidator cycleValidator,
            SyncStamper syncStamper,
            OrganizationSecurity orgSecurity,
            PieceService pieceService,
            SyncService syncService,
            PlatformTransactionManager transactionManager
    ) {
        this.mutationRepository = mutationRepository;
        this.syncReadRepository = syncReadRepository;
        this.locationRepository = locationRepository;
        this.pieceTypeRepository = pieceTypeRepository;
        this.pieceTypeAttributeRepository = pieceTypeAttributeRepository;
        this.orgAttributeRepository = orgAttributeRepository;
        this.organizationService = organizationService;
        this.cycleValidator = cycleValidator;
        this.syncStamper = syncStamper;
        this.orgSecurity = orgSecurity;
        this.pieceService = pieceService;
        this.syncService = syncService;
        this.requiresNewTx = new TransactionTemplate(transactionManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Applies the batch and returns one result per mutation, in order.
     *
     * @param orgId   organization id
     * @param orgSlug organization slug (for per-mutation authorization)
     * @param request the mutation batch
     * @return per-mutation outcomes
     */
    @Transactional
    public SyncMutationsResponse push(Integer orgId, String orgSlug, SyncMutationRequest request) {
        List<Result> results = new ArrayList<>();
        if (request != null && request.mutations() != null) {
            for (SyncMutationRequest.Item item : request.mutations()) {
                results.add(processOne(orgId, orgSlug, item));
            }
        }
        return new SyncMutationsResponse(results, SyncService.MIN_CLIENT_VERSION);
    }

    private Result processOne(Integer orgId, String orgSlug, SyncMutationRequest.Item item) {
        // Idempotency: a previously processed mutation id never re-applies (R24).
        if (mutationRepository.existsById(item.mutationId())) {
            return new Result(item.mutationId(), SyncMutationsResponse.STATUS_DUPLICATE,
                    item.syncId(), currentDoc(item), null);
        }

        Result result = switch (item.collection() == null ? "" : item.collection()) {
            case COLLECTION_LOCATIONS -> handleLocation(orgId, orgSlug, item);
            case COLLECTION_PIECE_TYPES -> handlePieceType(orgId, orgSlug, item);
            case COLLECTION_PIECE_TYPE_ATTRIBUTES -> handlePieceTypeAttribute(orgSlug, item);
            case COLLECTION_ORG_ATTRIBUTES -> handleOrgAttribute(orgId, orgSlug, item);
            case COLLECTION_PIECES -> handlePiece(orgId, orgSlug, item);
            default -> rejected(item, ERR_UNSUPPORTED_COLLECTION);
        };

        // Record only state-changing outcomes so a fixed retry (rejected) can be re-evaluated.
        if (SyncMutationsResponse.STATUS_APPLIED.equals(result.status())
                || SyncMutationsResponse.STATUS_CONFLICT.equals(result.status())) {
            mutationRepository.save(new SyncMutation()
                    .setMutationId(item.mutationId())
                    .setOrganizationId(orgId)
                    .setAppliedRev(revOf(result.serverDoc())));
        }
        return result;
    }

    private Result handleLocation(Integer orgId, String orgSlug, SyncMutationRequest.Item item) {
        User user = currentUser();
        if (user == null || !orgSecurity.canManageOrgContent(orgSlug, user)) {
            return rejected(item, ERR_PERMISSION_DENIED);
        }

        LocationSyncRow state = syncReadRepository.findLocationBySyncId(item.syncId());

        if (OP_DELETE.equals(item.op())) {
            return handleLocationDelete(item, state);
        }
        if (OP_UPSERT.equals(item.op())) {
            return handleLocationUpsert(orgId, item, state);
        }
        return rejected(item, ERR_UNSUPPORTED_OP);
    }

    private Result handleLocationDelete(SyncMutationRequest.Item item, LocationSyncRow state) {
        if (state == null || state.getDeletedAt() != null) {
            // Already gone or already a tombstone: idempotent no-op.
            return applied(item, state == null ? null : toDto(state));
        }
        Location location = locationRepository.findBySyncId(item.syncId()).orElseThrow();
        location.setDeletedAt(LocalDateTime.now());
        syncStamper.stamp(location);
        Location saved = locationRepository.save(location);
        return applied(item, toDto(saved));
    }

    private Result handleLocationUpsert(Integer orgId, SyncMutationRequest.Item item, LocationSyncRow state) {
        // Sticky delete: never resurrect a server-side tombstone (R7).
        if (state != null && state.getDeletedAt() != null) {
            return new Result(item.mutationId(), SyncMutationsResponse.STATUS_REJECTED,
                    item.syncId(), toDto(state), ERR_DELETED_UPSTREAM);
        }

        String name = readText(item.doc(), "name");
        if (name == null || name.isBlank()) {
            return rejected(item, ERR_VALIDATION_FAILED);
        }
        String description = readText(item.doc(), "description");
        String parentSyncId = readText(item.doc(), "parentSyncId");

        Location parent = null;
        if (parentSyncId != null) {
            Optional<Location> parentOpt = locationRepository.findBySyncId(parentSyncId);
            if (parentOpt.isEmpty()) {
                return rejected(item, ERR_DEPENDENCY_FAILED);
            }
            parent = parentOpt.get();
        }

        if (state == null) {
            Organization org = organizationService.findById(orgId);
            Location location = new Location()
                    .setOrganization(org)
                    .setName(name.trim())
                    .setDescription(emptyToNull(description))
                    .setParent(parent);
            location.setSyncId(item.syncId());
            syncStamper.stamp(location);
            Location saved = locationRepository.save(location);
            return applied(item, toDto(saved));
        }

        Location location = locationRepository.findBySyncId(item.syncId()).orElseThrow();
        try {
            cycleValidator.ensureNoCycle(location, parent);
        } catch (ResponseStatusException cycle) {
            return new Result(item.mutationId(), SyncMutationsResponse.STATUS_REJECTED,
                    item.syncId(), toDto(state), ERR_CYCLE);
        }
        location.setName(name.trim());
        location.setDescription(emptyToNull(description));
        location.setParent(parent);
        syncStamper.stamp(location);
        Location saved = locationRepository.save(location);

        boolean stale = item.baseRev() != null && item.baseRev() < state.getRev();
        String status = stale
                ? SyncMutationsResponse.STATUS_CONFLICT
                : SyncMutationsResponse.STATUS_APPLIED;
        return new Result(item.mutationId(), status, item.syncId(), toDto(saved), null);
    }

    private Result handlePieceType(Integer orgId, String orgSlug, SyncMutationRequest.Item item) {
        User user = currentUser();
        if (user == null || !orgSecurity.canManageOrgContent(orgSlug, user)) {
            return rejected(item, ERR_PERMISSION_DENIED);
        }
        PieceTypeSyncRow state = syncReadRepository.findPieceTypeBySyncId(item.syncId());
        if (OP_DELETE.equals(item.op())) {
            return handlePieceTypeDelete(item, state);
        }
        if (OP_UPSERT.equals(item.op())) {
            return handlePieceTypeUpsert(orgId, item, state);
        }
        return rejected(item, ERR_UNSUPPORTED_OP);
    }

    private Result handlePieceTypeDelete(SyncMutationRequest.Item item, PieceTypeSyncRow state) {
        if (state == null || state.getDeletedAt() != null) {
            return applied(item, state == null ? null : toDto(state));
        }
        PieceType type = pieceTypeRepository.findBySyncId(item.syncId()).orElseThrow();
        // Free the (org, name) unique slot so a new active type can reuse the name (matches
        // PieceTypeService's soft-delete name mangling).
        type.setName(buildSoftDeletedName(type.getName(), type.getId(), MAX_TYPE_NAME_LENGTH));
        type.setDeletedAt(LocalDateTime.now());
        syncStamper.stamp(type);
        return applied(item, toDto(pieceTypeRepository.save(type)));
    }

    private Result handlePieceTypeUpsert(Integer orgId, SyncMutationRequest.Item item, PieceTypeSyncRow state) {
        if (state != null && state.getDeletedAt() != null) {
            return new Result(item.mutationId(), SyncMutationsResponse.STATUS_REJECTED,
                    item.syncId(), toDto(state), ERR_DELETED_UPSTREAM);
        }
        String name = readText(item.doc(), "name");
        if (name == null || name.isBlank()) {
            return rejected(item, ERR_VALIDATION_FAILED);
        }
        String trimmed = name.trim();

        if (state == null) {
            Organization org = organizationService.findById(orgId);
            // Pre-check uniqueness instead of relying on a constraint violation, which would taint
            // the batch transaction.
            if (pieceTypeRepository.findByOrganizationAndName(org, trimmed).isPresent()) {
                return rejected(item, ERR_NAME_CONFLICT);
            }
            PieceType type = new PieceType().setOrganization(org).setName(trimmed);
            type.setSyncId(item.syncId());
            syncStamper.stamp(type);
            return applied(item, toDto(pieceTypeRepository.save(type)));
        }

        PieceType type = pieceTypeRepository.findBySyncId(item.syncId()).orElseThrow();
        Optional<PieceType> clash = pieceTypeRepository.findByOrganizationAndName(type.getOrganization(), trimmed);
        if (clash.isPresent() && !clash.get().getSyncId().equals(item.syncId())) {
            return rejected(item, ERR_NAME_CONFLICT);
        }
        type.setName(trimmed);
        syncStamper.stamp(type);
        PieceType saved = pieceTypeRepository.save(type);
        boolean stale = item.baseRev() != null && item.baseRev() < state.getRev();
        String status = stale
                ? SyncMutationsResponse.STATUS_CONFLICT
                : SyncMutationsResponse.STATUS_APPLIED;
        return new Result(item.mutationId(), status, item.syncId(), toDto(saved), null);
    }

    private static String buildSoftDeletedName(String name, Integer id, int maxLength) {
        String suffix = SOFT_DELETE_SUFFIX + (id == null ? "?" : id);
        int maxBase = Math.max(0, maxLength - suffix.length());
        String base = name == null ? "" : name;
        if (base.length() > maxBase) {
            base = base.substring(0, maxBase);
        }
        return base + suffix;
    }

    private Result handleOrgAttribute(Integer orgId, String orgSlug, SyncMutationRequest.Item item) {
        User user = currentUser();
        if (user == null || !orgSecurity.canManageOrgContent(orgSlug, user)) {
            return rejected(item, ERR_PERMISSION_DENIED);
        }
        OrgAttributeSyncRow state = syncReadRepository.findOrgAttributeBySyncId(item.syncId());
        if (OP_DELETE.equals(item.op())) {
            return handleOrgAttributeDelete(item, state);
        }
        if (OP_UPSERT.equals(item.op())) {
            return handleOrgAttributeUpsert(orgId, item, state);
        }
        return rejected(item, ERR_UNSUPPORTED_OP);
    }

    private Result handleOrgAttributeDelete(SyncMutationRequest.Item item, OrgAttributeSyncRow state) {
        if (state == null || state.getDeletedAt() != null) {
            return applied(item, state == null ? null : toDto(state));
        }
        OrganizationPieceAttribute attr = orgAttributeRepository.findBySyncId(item.syncId()).orElseThrow();
        attr.setName(buildSoftDeletedName(attr.getName(), attr.getId(), MAX_ATTR_NAME_LENGTH));
        attr.setDeletedAt(LocalDateTime.now());
        syncStamper.stamp(attr);
        return applied(item, toDto(orgAttributeRepository.save(attr)));
    }

    private Result handleOrgAttributeUpsert(Integer orgId, SyncMutationRequest.Item item, OrgAttributeSyncRow state) {
        if (state != null && state.getDeletedAt() != null) {
            return new Result(item.mutationId(), SyncMutationsResponse.STATUS_REJECTED,
                    item.syncId(), toDto(state), ERR_DELETED_UPSTREAM);
        }
        String name = readText(item.doc(), "name");
        if (name == null || name.isBlank()) {
            return rejected(item, ERR_VALIDATION_FAILED);
        }
        AttributeType type = parseAttributeType(readText(item.doc(), "type"));
        if (state == null && type == null) {
            return rejected(item, ERR_VALIDATION_FAILED);
        }
        String trimmed = name.trim();
        String displayName = orDefault(readText(item.doc(), "displayName"), trimmed);
        boolean required = readBool(item.doc(), "required", true);
        int position = readInt(item.doc(), "position", 0);
        String validatorsJson = readText(item.doc(), "validatorsJson");

        if (state == null) {
            Organization org = organizationService.findById(orgId);
            if (orgAttributeRepository.findByOrganizationAndName(org, trimmed).isPresent()) {
                return rejected(item, ERR_NAME_CONFLICT);
            }
            OrganizationPieceAttribute attr = new OrganizationPieceAttribute()
                    .setOrganization(org)
                    .setName(trimmed)
                    .setDisplayName(displayName)
                    .setType(type)
                    .setRequired(required)
                    .setPosition(position)
                    .setValidatorsJson(validatorsJson);
            attr.setSyncId(item.syncId());
            syncStamper.stamp(attr);
            return applied(item, toDto(orgAttributeRepository.save(attr)));
        }

        OrganizationPieceAttribute attr = orgAttributeRepository.findBySyncId(item.syncId()).orElseThrow();
        Optional<OrganizationPieceAttribute> clash =
                orgAttributeRepository.findByOrganizationAndName(attr.getOrganization(), trimmed);
        if (clash.isPresent() && !clash.get().getSyncId().equals(item.syncId())) {
            return rejected(item, ERR_NAME_CONFLICT);
        }
        attr.setName(trimmed)
                .setDisplayName(displayName)
                .setRequired(required)
                .setPosition(position)
                .setValidatorsJson(validatorsJson);
        if (type != null) {
            attr.setType(type);
        }
        syncStamper.stamp(attr);
        OrganizationPieceAttribute saved = orgAttributeRepository.save(attr);
        boolean stale = item.baseRev() != null && item.baseRev() < state.getRev();
        String status = stale
                ? SyncMutationsResponse.STATUS_CONFLICT
                : SyncMutationsResponse.STATUS_APPLIED;
        return new Result(item.mutationId(), status, item.syncId(), toDto(saved), null);
    }

    private Result handlePieceTypeAttribute(String orgSlug, SyncMutationRequest.Item item) {
        User user = currentUser();
        if (user == null || !orgSecurity.canManageOrgContent(orgSlug, user)) {
            return rejected(item, ERR_PERMISSION_DENIED);
        }
        PieceTypeAttributeSyncRow state = syncReadRepository.findPieceTypeAttributeBySyncId(item.syncId());
        if (OP_DELETE.equals(item.op())) {
            return handlePieceTypeAttributeDelete(item, state);
        }
        if (OP_UPSERT.equals(item.op())) {
            return handlePieceTypeAttributeUpsert(item, state);
        }
        return rejected(item, ERR_UNSUPPORTED_OP);
    }

    private Result handlePieceTypeAttributeDelete(SyncMutationRequest.Item item, PieceTypeAttributeSyncRow state) {
        if (state == null || state.getDeletedAt() != null) {
            return applied(item, state == null ? null : toDto(state));
        }
        PieceTypeAttribute attr = pieceTypeAttributeRepository.findBySyncId(item.syncId()).orElseThrow();
        attr.setName(buildSoftDeletedName(attr.getName(), attr.getId(), MAX_ATTR_NAME_LENGTH));
        attr.setDeletedAt(LocalDateTime.now());
        syncStamper.stamp(attr);
        return applied(item, toDto(pieceTypeAttributeRepository.save(attr)));
    }

    private Result handlePieceTypeAttributeUpsert(SyncMutationRequest.Item item, PieceTypeAttributeSyncRow state) {
        if (state != null && state.getDeletedAt() != null) {
            return new Result(item.mutationId(), SyncMutationsResponse.STATUS_REJECTED,
                    item.syncId(), toDto(state), ERR_DELETED_UPSTREAM);
        }
        String name = readText(item.doc(), "name");
        if (name == null || name.isBlank()) {
            return rejected(item, ERR_VALIDATION_FAILED);
        }
        AttributeType type = parseAttributeType(readText(item.doc(), "type"));
        if (state == null && type == null) {
            return rejected(item, ERR_VALIDATION_FAILED);
        }
        String trimmed = name.trim();
        String displayName = orDefault(readText(item.doc(), "displayName"), trimmed);
        boolean required = readBool(item.doc(), "required", true);
        int position = readInt(item.doc(), "position", 0);
        String validatorsJson = readText(item.doc(), "validatorsJson");

        if (state == null) {
            String pieceTypeSyncId = readText(item.doc(), "pieceTypeSyncId");
            if (pieceTypeSyncId == null) {
                return rejected(item, ERR_DEPENDENCY_FAILED);
            }
            Optional<PieceType> typeOpt = pieceTypeRepository.findBySyncId(pieceTypeSyncId);
            if (typeOpt.isEmpty()) {
                return rejected(item, ERR_DEPENDENCY_FAILED);
            }
            PieceType pieceType = typeOpt.get();
            if (pieceTypeAttributeRepository.findByPieceTypeAndName(pieceType, trimmed).isPresent()) {
                return rejected(item, ERR_NAME_CONFLICT);
            }
            PieceTypeAttribute attr = new PieceTypeAttribute()
                    .setPieceType(pieceType)
                    .setName(trimmed)
                    .setDisplayName(displayName)
                    .setType(type)
                    .setRequired(required)
                    .setPosition(position)
                    .setValidatorsJson(validatorsJson);
            attr.setSyncId(item.syncId());
            syncStamper.stamp(attr);
            return applied(item, toDto(pieceTypeAttributeRepository.save(attr)));
        }

        PieceTypeAttribute attr = pieceTypeAttributeRepository.findBySyncId(item.syncId()).orElseThrow();
        Optional<PieceTypeAttribute> clash =
                pieceTypeAttributeRepository.findByPieceTypeAndName(attr.getPieceType(), trimmed);
        if (clash.isPresent() && !clash.get().getSyncId().equals(item.syncId())) {
            return rejected(item, ERR_NAME_CONFLICT);
        }
        attr.setName(trimmed)
                .setDisplayName(displayName)
                .setRequired(required)
                .setPosition(position)
                .setValidatorsJson(validatorsJson);
        if (type != null) {
            attr.setType(type);
        }
        syncStamper.stamp(attr);
        PieceTypeAttribute saved = pieceTypeAttributeRepository.save(attr);
        boolean stale = item.baseRev() != null && item.baseRev() < state.getRev();
        String status = stale
                ? SyncMutationsResponse.STATUS_CONFLICT
                : SyncMutationsResponse.STATUS_APPLIED;
        return new Result(item.mutationId(), status, item.syncId(), toDto(saved), null);
    }

    private Result handlePiece(Integer orgId, String orgSlug, SyncMutationRequest.Item item) {
        User user = currentUser();
        if (user == null || !orgSecurity.canWritePieces(orgSlug, user)) {
            return rejected(item, ERR_PERMISSION_DENIED);
        }
        PieceStateRow state = syncReadRepository.findPieceStateBySyncId(item.syncId());
        if (OP_DELETE.equals(item.op())) {
            return handlePieceDelete(orgId, item, state);
        }
        if (OP_UPSERT.equals(item.op())) {
            return handlePieceUpsert(orgId, item, state);
        }
        return rejected(item, ERR_UNSUPPORTED_OP);
    }

    private Result handlePieceDelete(Integer orgId, SyncMutationRequest.Item item, PieceStateRow state) {
        if (state == null || state.getDeletedAt() != null) {
            return applied(item, state == null ? null : syncService.pieceDocBySyncId(item.syncId()));
        }
        Integer pieceId = state.getId();
        try {
            requiresNewTx.executeWithoutResult(s -> pieceService.softDelete(orgId, pieceId));
        } catch (ResponseStatusException ex) {
            return mapPieceException(item, ex);
        }
        return applied(item, syncService.pieceDocBySyncId(item.syncId()));
    }

    private Result handlePieceUpsert(Integer orgId, SyncMutationRequest.Item item, PieceStateRow state) {
        // Sticky delete: never resurrect a server-side tombstone (R7).
        if (state != null && state.getDeletedAt() != null) {
            return new Result(item.mutationId(), SyncMutationsResponse.STATUS_REJECTED,
                    item.syncId(), syncService.pieceDocBySyncId(item.syncId()), ERR_DELETED_UPSTREAM);
        }
        PieceRefs refs = resolvePieceRefs(item.doc());
        if (refs.errorCode() != null) {
            return rejected(item, refs.errorCode());
        }
        try {
            if (state == null) {
                CreatePieceDto dto = buildCreatePieceDto(item.doc(), refs);
                // Reuse PieceService inside an isolated transaction so a validation failure rolls
                // back only this piece (and its partial attribute values), not the whole batch.
                requiresNewTx.executeWithoutResult(s -> pieceService.create(orgId, dto, item.syncId()));
                return applied(item, syncService.pieceDocBySyncId(item.syncId()));
            }
            UpdatePieceDto dto = buildUpdatePieceDto(item.doc(), refs);
            Integer pieceId = state.getId();
            requiresNewTx.executeWithoutResult(s -> pieceService.update(orgId, pieceId, dto));
            boolean stale = item.baseRev() != null && item.baseRev() < state.getRev();
            String status = stale
                    ? SyncMutationsResponse.STATUS_CONFLICT
                    : SyncMutationsResponse.STATUS_APPLIED;
            return new Result(item.mutationId(), status, item.syncId(),
                    syncService.pieceDocBySyncId(item.syncId()), null);
        } catch (ResponseStatusException ex) {
            return mapPieceException(item, ex);
        }
    }

    private Result mapPieceException(SyncMutationRequest.Item item, ResponseStatusException ex) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        log.debug("sync_piece_rejected mutationId={} status={}", item.mutationId(), status);
        if (status == HttpStatus.CONFLICT) {
            return rejected(item, ERR_SERIAL_CONFLICT);
        }
        if (status == HttpStatus.NOT_FOUND) {
            return rejected(item, ERR_DEPENDENCY_FAILED);
        }
        return rejected(item, ERR_VALIDATION_FAILED);
    }

    /** Resolved numeric references for a piece mutation (or an error code when a ref is missing). */
    private record PieceRefs(
            String errorCode,
            List<Integer> typeIds,
            Integer locationId,
            boolean hasLocationKey,
            Integer ownerUserId,
            boolean hasOwnerKey,
            List<AttributeValueInputDto> attributeValues
    ) {
        static PieceRefs error(String code) {
            return new PieceRefs(code, null, null, false, null, false, null);
        }
    }

    private PieceRefs resolvePieceRefs(JsonNode doc) {
        List<Integer> typeIds = new ArrayList<>();
        JsonNode typeArr = doc == null ? null : doc.get("pieceTypeSyncIds");
        if (typeArr != null && typeArr.isArray()) {
            for (JsonNode node : typeArr) {
                String sid = node.asText(null);
                if (sid == null) {
                    continue;
                }
                Optional<PieceType> type = pieceTypeRepository.findBySyncId(sid);
                if (type.isEmpty()) {
                    return PieceRefs.error(ERR_DEPENDENCY_FAILED);
                }
                typeIds.add(type.get().getId());
            }
        }

        boolean hasLocationKey = doc != null && doc.has("locationSyncId");
        Integer locationId = null;
        if (doc != null && doc.hasNonNull("locationSyncId")) {
            Optional<Location> location = locationRepository.findBySyncId(doc.get("locationSyncId").asText());
            if (location.isEmpty()) {
                return PieceRefs.error(ERR_DEPENDENCY_FAILED);
            }
            locationId = location.get().getId();
        }

        boolean hasOwnerKey = doc != null && doc.has("ownerUserId");
        Integer ownerUserId = doc != null && doc.hasNonNull("ownerUserId") ? doc.get("ownerUserId").asInt() : null;

        List<AttributeValueInputDto> values = new ArrayList<>();
        String typeValuesErr = collectAttributeValues(doc, "typeAttributeValues", AttributeScope.TYPE, values);
        if (typeValuesErr != null) {
            return PieceRefs.error(typeValuesErr);
        }
        String orgValuesErr = collectAttributeValues(doc, "orgAttributeValues", AttributeScope.ORG, values);
        if (orgValuesErr != null) {
            return PieceRefs.error(orgValuesErr);
        }
        return new PieceRefs(null, typeIds, locationId, hasLocationKey, ownerUserId, hasOwnerKey, values);
    }

    private String collectAttributeValues(JsonNode doc, String field, AttributeScope scope,
                                          List<AttributeValueInputDto> out) {
        JsonNode arr = doc == null ? null : doc.get(field);
        if (arr == null || !arr.isArray()) {
            return null;
        }
        for (JsonNode node : arr) {
            String attrSyncId = node.hasNonNull("attributeSyncId") ? node.get("attributeSyncId").asText() : null;
            if (attrSyncId == null) {
                continue;
            }
            String value = node.hasNonNull("value") ? node.get("value").asText() : null;
            Integer attrId;
            if (scope == AttributeScope.ORG) {
                Optional<OrganizationPieceAttribute> attr = orgAttributeRepository.findBySyncId(attrSyncId);
                if (attr.isEmpty()) {
                    return ERR_DEPENDENCY_FAILED;
                }
                attrId = attr.get().getId();
            } else {
                Optional<PieceTypeAttribute> attr = pieceTypeAttributeRepository.findBySyncId(attrSyncId);
                if (attr.isEmpty()) {
                    return ERR_DEPENDENCY_FAILED;
                }
                attrId = attr.get().getId();
            }
            out.add(new AttributeValueInputDto(attrId, scope, value));
        }
        return null;
    }

    private CreatePieceDto buildCreatePieceDto(JsonNode doc, PieceRefs refs) {
        return new CreatePieceDto()
                .setName(readText(doc, "name"))
                .setSerialNumber(readText(doc, "serialNumber"))
                .setDescription(readText(doc, "description"))
                .setPieceTypeIds(refs.typeIds())
                .setLocationId(refs.locationId())
                .setOwnerUserId(refs.ownerUserId())
                .setAttributeValues(refs.attributeValues());
    }

    private UpdatePieceDto buildUpdatePieceDto(JsonNode doc, PieceRefs refs) {
        UpdatePieceDto dto = new UpdatePieceDto()
                .setName(readText(doc, "name"))
                .setSerialNumber(readText(doc, "serialNumber"))
                .setDescription(readText(doc, "description"))
                .setPieceTypeIds(refs.typeIds())
                .setAttributeValues(refs.attributeValues());
        if (refs.locationId() != null) {
            dto.setLocationId(refs.locationId());
        } else if (refs.hasLocationKey()) {
            dto.setClearLocation(true);
        }
        if (refs.ownerUserId() != null) {
            dto.setOwnerUserId(refs.ownerUserId());
        } else if (refs.hasOwnerKey()) {
            dto.setClearOwner(true);
        }
        return dto;
    }

    private Object currentDoc(SyncMutationRequest.Item item) {
        return switch (item.collection() == null ? "" : item.collection()) {
            case COLLECTION_LOCATIONS -> currentLocationDoc(item.syncId());
            case COLLECTION_PIECE_TYPES -> {
                PieceTypeSyncRow row = syncReadRepository.findPieceTypeBySyncId(item.syncId());
                yield row == null ? null : toDto(row);
            }
            case COLLECTION_ORG_ATTRIBUTES -> {
                OrgAttributeSyncRow row = syncReadRepository.findOrgAttributeBySyncId(item.syncId());
                yield row == null ? null : toDto(row);
            }
            case COLLECTION_PIECE_TYPE_ATTRIBUTES -> {
                PieceTypeAttributeSyncRow row = syncReadRepository.findPieceTypeAttributeBySyncId(item.syncId());
                yield row == null ? null : toDto(row);
            }
            case COLLECTION_PIECES -> syncService.pieceDocBySyncId(item.syncId());
            default -> null;
        };
    }

    private LocationSyncDto currentLocationDoc(String syncId) {
        LocationSyncRow row = syncReadRepository.findLocationBySyncId(syncId);
        return row == null ? null : toDto(row);
    }

    private static Result applied(SyncMutationRequest.Item item, Object serverDoc) {
        return new Result(item.mutationId(), SyncMutationsResponse.STATUS_APPLIED,
                item.syncId(), serverDoc, null);
    }

    private static Result rejected(SyncMutationRequest.Item item, String errorCode) {
        return new Result(item.mutationId(), SyncMutationsResponse.STATUS_REJECTED,
                item.syncId(), null, errorCode);
    }

    private static Long revOf(Object serverDoc) {
        if (serverDoc instanceof LocationSyncDto dto) {
            return dto.rev();
        }
        if (serverDoc instanceof PieceTypeSyncDto dto) {
            return dto.rev();
        }
        if (serverDoc instanceof OrgAttributeSyncDto dto) {
            return dto.rev();
        }
        if (serverDoc instanceof PieceTypeAttributeSyncDto dto) {
            return dto.rev();
        }
        if (serverDoc instanceof PieceSyncDto dto) {
            return dto.rev();
        }
        return null;
    }

    private static LocationSyncDto toDto(Location l) {
        return new LocationSyncDto(
                l.getSyncId(),
                l.getRev() == null ? 0L : l.getRev(),
                l.getName(),
                l.getDescription(),
                l.getParent() == null ? null : l.getParent().getSyncId(),
                toLocalDateTime(l.getCreatedAt()),
                toLocalDateTime(l.getUpdatedAt()),
                l.getDeletedAt());
    }

    private static LocationSyncDto toDto(LocationSyncRow r) {
        return new LocationSyncDto(
                r.getSyncId(), r.getRev(), r.getName(), r.getDescription(),
                r.getParentSyncId(), r.getCreatedAt(), r.getUpdatedAt(), r.getDeletedAt());
    }

    private static PieceTypeSyncDto toDto(PieceType t) {
        return new PieceTypeSyncDto(
                t.getSyncId(),
                t.getRev() == null ? 0L : t.getRev(),
                t.getName(),
                toLocalDateTime(t.getCreatedAt()),
                toLocalDateTime(t.getUpdatedAt()),
                t.getDeletedAt());
    }

    private static PieceTypeSyncDto toDto(PieceTypeSyncRow r) {
        return new PieceTypeSyncDto(
                r.getSyncId(), r.getRev(), r.getName(),
                r.getCreatedAt(), r.getUpdatedAt(), r.getDeletedAt());
    }

    private static OrgAttributeSyncDto toDto(OrganizationPieceAttribute a) {
        return new OrgAttributeSyncDto(
                a.getSyncId(),
                a.getRev() == null ? 0L : a.getRev(),
                a.getName(),
                a.getDisplayName(),
                a.getType() == null ? null : a.getType().name(),
                a.isRequired(),
                a.getPosition(),
                a.getValidatorsJson(),
                toLocalDateTime(a.getCreatedAt()),
                toLocalDateTime(a.getUpdatedAt()),
                a.getDeletedAt());
    }

    private static OrgAttributeSyncDto toDto(OrgAttributeSyncRow r) {
        return new OrgAttributeSyncDto(
                r.getSyncId(), r.getRev(), r.getName(), r.getDisplayName(), r.getAttrType(),
                r.getIsRequired(), r.getAttrPosition(), r.getValidatorsJson(),
                r.getCreatedAt(), r.getUpdatedAt(), r.getDeletedAt());
    }

    private static PieceTypeAttributeSyncDto toDto(PieceTypeAttribute a) {
        return new PieceTypeAttributeSyncDto(
                a.getSyncId(),
                a.getRev() == null ? 0L : a.getRev(),
                a.getPieceType() == null ? null : a.getPieceType().getSyncId(),
                a.getName(),
                a.getDisplayName(),
                a.getType() == null ? null : a.getType().name(),
                a.isRequired(),
                a.getPosition(),
                a.getValidatorsJson(),
                toLocalDateTime(a.getCreatedAt()),
                toLocalDateTime(a.getUpdatedAt()),
                a.getDeletedAt());
    }

    private static PieceTypeAttributeSyncDto toDto(PieceTypeAttributeSyncRow r) {
        return new PieceTypeAttributeSyncDto(
                r.getSyncId(), r.getRev(), r.getPieceTypeSyncId(), r.getName(), r.getDisplayName(),
                r.getAttrType(), r.getIsRequired(), r.getAttrPosition(), r.getValidatorsJson(),
                r.getCreatedAt(), r.getUpdatedAt(), r.getDeletedAt());
    }

    private static AttributeType parseAttributeType(String value) {
        if (value == null) {
            return null;
        }
        try {
            return AttributeType.valueOf(value);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static LocalDateTime toLocalDateTime(Date date) {
        return date == null ? null : LocalDateTime.ofInstant(date.toInstant(), ZoneOffset.UTC);
    }

    private static String readText(JsonNode doc, String field) {
        return doc != null && doc.hasNonNull(field) ? doc.get(field).asText() : null;
    }

    private static boolean readBool(JsonNode doc, String field, boolean defaultValue) {
        return doc != null && doc.hasNonNull(field) ? doc.get(field).asBoolean(defaultValue) : defaultValue;
    }

    private static int readInt(JsonNode doc, String field, int defaultValue) {
        return doc != null && doc.hasNonNull(field) ? doc.get(field).asInt(defaultValue) : defaultValue;
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String emptyToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof User user)) {
            return null;
        }
        return user;
    }
}
