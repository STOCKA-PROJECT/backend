package com.stocka.backend.modules.pieces.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.stocka.backend.modules.common.error.ApiException;
import com.stocka.backend.modules.common.error.ErrorCodes;
import com.stocka.backend.modules.locations.entity.Location;
import com.stocka.backend.modules.locations.repository.LocationRepository;
import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.organizations.entity.OrganizationPieceAttribute;
import com.stocka.backend.modules.organizations.repository.OrganizationMemberRepository;
import com.stocka.backend.modules.organizations.repository.OrganizationPieceAttributeRepository;
import com.stocka.backend.modules.organizations.service.OrganizationQuotaProperties;
import com.stocka.backend.modules.organizations.service.OrganizationService;
import com.stocka.backend.modules.pieces.dto.AttributeScope;
import com.stocka.backend.modules.pieces.dto.AttributeValueInputDto;
import com.stocka.backend.modules.pieces.dto.CreatePieceDto;
import com.stocka.backend.modules.pieces.dto.UpdatePieceDto;
import com.stocka.backend.modules.pieces.entity.Piece;
import com.stocka.backend.modules.pieces.entity.PieceAttachment;
import com.stocka.backend.modules.pieces.entity.PieceAttachmentKind;
import com.stocka.backend.modules.pieces.entity.PieceAttributeValue;
import com.stocka.backend.modules.pieces.entity.PieceOrganizationAttributeValue;
import com.stocka.backend.modules.pieces.entity.PieceStatus;
import com.stocka.backend.modules.pieces.repository.PieceAttachmentRepository;
import com.stocka.backend.modules.pieces.repository.PieceAttributeValueRepository;
import com.stocka.backend.modules.pieces.repository.PieceOrganizationAttributeValueRepository;
import com.stocka.backend.modules.pieces.repository.PieceRepository;
import com.stocka.backend.modules.pieces.service.attributevalidation.AttributeValueValidationRegistry;
import com.stocka.backend.modules.piecetypes.entity.PieceType;
import com.stocka.backend.modules.piecetypes.entity.PieceTypeAttribute;
import com.stocka.backend.modules.piecetypes.service.PieceTypeService;
import com.stocka.backend.modules.users.entity.User;
import com.stocka.backend.modules.users.repository.UserRepository;

import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;

/**
 * CRUD and lookup for {@link Piece}. Validates cross-org references, normalizes attribute values
 * through the strategy registry, recalculates status on every mutation, and records every
 * relevant change in the piece history.
 *
 * <p>A piece can be attached to one or more {@link PieceType}s; its attribute schema is the
 * union of all attributes across the attached types. The status is {@link PieceStatus#ACTIVE}
 * only when every required attribute from every attached type has a non-blank value.
 */
@Service
public class PieceService {
    private static final int MAX_NAME_LENGTH = 255;
    private static final int MAX_SERIAL_LENGTH = 100;
    private static final int MAX_PAGE_SIZE = 100;

    private final PieceRepository pieceRepository;
    private final PieceAttributeValueRepository valueRepository;
    private final PieceOrganizationAttributeValueRepository orgValueRepository;
    private final OrganizationPieceAttributeRepository orgAttributeRepository;
    private final PieceAttachmentRepository attachmentRepository;
    private final PieceStatusCalculator statusCalculator;
    private final PieceHistoryService historyService;
    private final OrganizationService organizationService;
    private final OrganizationMemberRepository memberRepository;
    private final PieceTypeService pieceTypeService;
    private final AttributeValueValidationRegistry validationRegistry;
    private final LocationRepository locationRepository;
    private final UserRepository userRepository;
    private final OrganizationQuotaProperties quotas;

    public PieceService(
            PieceRepository pieceRepository,
            PieceAttributeValueRepository valueRepository,
            PieceOrganizationAttributeValueRepository orgValueRepository,
            OrganizationPieceAttributeRepository orgAttributeRepository,
            PieceAttachmentRepository attachmentRepository,
            PieceStatusCalculator statusCalculator,
            PieceHistoryService historyService,
            OrganizationService organizationService,
            OrganizationMemberRepository memberRepository,
            PieceTypeService pieceTypeService,
            AttributeValueValidationRegistry validationRegistry,
            LocationRepository locationRepository,
            UserRepository userRepository,
            OrganizationQuotaProperties quotas
    ) {
        this.pieceRepository = pieceRepository;
        this.valueRepository = valueRepository;
        this.orgValueRepository = orgValueRepository;
        this.orgAttributeRepository = orgAttributeRepository;
        this.attachmentRepository = attachmentRepository;
        this.statusCalculator = statusCalculator;
        this.historyService = historyService;
        this.organizationService = organizationService;
        this.memberRepository = memberRepository;
        this.pieceTypeService = pieceTypeService;
        this.validationRegistry = validationRegistry;
        this.locationRepository = locationRepository;
        this.userRepository = userRepository;
        this.quotas = quotas;
    }

    @Transactional
    public Piece create(Integer orgId, CreatePieceDto dto) {
        Organization org = organizationService.findById(orgId);
        ensureUnderPiecesPerOrgQuota(org);
        Set<PieceType> types = resolveTypes(orgId, dto.getPieceTypeIds());

        String name = sanitizeName(dto.getName());
        String serialNumber = sanitizeSerialNumber(dto.getSerialNumber());
        ensureSerialNumberUnique(orgId, serialNumber, null);
        Location location = resolveLocation(org, dto.getLocationId(), false);
        User owner = resolveOwner(org, dto.getOwnerUserId(), false);

        Piece piece = new Piece()
                .setOrganization(org)
                .setPieceTypes(types)
                .setName(name)
                .setSerialNumber(serialNumber)
                .setDescription(emptyToNull(dto.getDescription()))
                .setLocation(location)
                .setOwner(owner)
                .setStatus(PieceStatus.PENDING);
        piece = pieceRepository.save(piece);

        Map<Integer, PieceTypeAttribute> typePool = collectTypeAttributePool(types);
        Map<Integer, OrganizationPieceAttribute> orgPool = collectOrgAttributePool(org);
        Map<Integer, PieceAttributeValue> typeValues = new HashMap<>();
        Map<Integer, PieceOrganizationAttributeValue> orgValues = new HashMap<>();
        if (dto.getAttributeValues() != null) {
            for (AttributeValueInputDto input : dto.getAttributeValues()) {
                if (input.effectiveScope() == AttributeScope.ORG) {
                    OrganizationPieceAttribute attribute = requireOrgAttributeInPool(orgPool, input.attributeId());
                    String normalized = validationRegistry.validate(
                            attribute.getType(), attribute.getValidatorsJson(),
                            attribute.isRequired(), input.value());
                    if (normalized != null) {
                        PieceOrganizationAttributeValue value = new PieceOrganizationAttributeValue()
                                .setPiece(piece)
                                .setAttribute(attribute)
                                .setValue(normalized);
                        orgValues.put(attribute.getId(), orgValueRepository.save(value));
                    }
                } else {
                    PieceTypeAttribute attribute = requireAttributeInPool(typePool, input.attributeId());
                    String normalized = validationRegistry.validate(attribute, input.value());
                    if (normalized != null) {
                        PieceAttributeValue value = new PieceAttributeValue()
                                .setPiece(piece)
                                .setAttribute(attribute)
                                .setValue(normalized);
                        typeValues.put(attribute.getId(), valueRepository.save(value));
                    }
                }
            }
        }

        PieceStatus status = statusCalculator.compute(
                typePool.values(), typeValues, orgPool.values(), orgValues);
        piece.setStatus(status);
        piece = pieceRepository.save(piece);

        User actor = currentUser();
        historyService.recordCreated(piece, actor);
        return piece;
    }

    public Piece findInOrg(Integer orgId, Integer pieceId) {
        Organization org = organizationService.findById(orgId);
        return pieceRepository.findByIdAndOrganization(pieceId, org)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.PIECES_NOT_FOUND));
    }

    public List<PieceAttributeValue> valuesOf(Piece piece) {
        return valueRepository.findByPiece(piece);
    }

    public List<PieceOrganizationAttributeValue> orgValuesOf(Piece piece) {
        return orgValueRepository.findByPiece(piece);
    }

    public Page<Piece> list(
            Integer orgId,
            Integer pieceTypeId,
            Integer locationId,
            Integer ownerUserId,
            PieceStatus status,
            String q,
            Pageable pageable
    ) {
        Organization org = organizationService.findById(orgId);
        Pageable bounded = boundPageable(pageable);
        Specification<Piece> spec = (root, query, cb) -> {
            List<Predicate> preds = new ArrayList<>();
            preds.add(cb.equal(root.get("organization"), org));
            if (pieceTypeId != null) {
                Join<Object, Object> typeJoin = root.join("pieceTypes");
                preds.add(cb.equal(typeJoin.get("id"), pieceTypeId));
                if (query != null) query.distinct(true);
            }
            if (locationId != null) preds.add(cb.equal(root.get("location").get("id"), locationId));
            if (ownerUserId != null) preds.add(cb.equal(root.get("owner").get("id"), ownerUserId));
            if (status != null) preds.add(cb.equal(root.get("status"), status));
            if (q != null && !q.isBlank()) {
                String pattern = "%" + q.trim().toLowerCase() + "%";
                preds.add(cb.or(
                        cb.like(cb.lower(root.get("name")), pattern),
                        cb.like(cb.lower(root.get("description")), pattern)
                ));
            }
            return cb.and(preds.toArray(new Predicate[0]));
        };
        return pieceRepository.findAll(spec, bounded);
    }

    @Transactional
    public Piece update(Integer orgId, Integer pieceId, UpdatePieceDto dto) {
        Piece piece = findInOrg(orgId, pieceId);
        Organization org = piece.getOrganization();
        User actor = currentUser();
        PieceStatus oldStatus = piece.getStatus();

        if (dto.getName() != null) {
            String newName = sanitizeName(dto.getName());
            if (!newName.equals(piece.getName())) {
                String old = piece.getName();
                piece.setName(newName);
                historyService.recordUpdated(piece, actor, "name", old, newName);
            }
        }
        if (dto.getSerialNumber() != null) {
            String newSerial = sanitizeSerialNumber(dto.getSerialNumber());
            if (!java.util.Objects.equals(piece.getSerialNumber(), newSerial)) {
                ensureSerialNumberUnique(orgId, newSerial, piece.getId());
                String old = piece.getSerialNumber();
                piece.setSerialNumber(newSerial);
                historyService.recordUpdated(piece, actor, "serialNumber", old, newSerial);
            }
        }
        if (dto.getDescription() != null) {
            String old = piece.getDescription();
            String value = emptyToNull(dto.getDescription());
            if (!java.util.Objects.equals(old, value)) {
                piece.setDescription(value);
                historyService.recordUpdated(piece, actor, "description", old, value);
            }
        }

        if (dto.getPieceTypeIds() != null) {
            applyTypesUpdate(orgId, piece, dto.getPieceTypeIds(), actor);
        }

        if (Boolean.TRUE.equals(dto.getClearOwner())) {
            if (piece.getOwner() != null) {
                String oldName = displayUserName(piece.getOwner());
                piece.setOwner(null);
                historyService.recordOwnerChanged(piece, actor, oldName, null);
            }
        } else if (dto.getOwnerUserId() != null) {
            User newOwner = resolveOwner(org, dto.getOwnerUserId(), true);
            Integer oldId = piece.getOwner() == null ? null : piece.getOwner().getId();
            Integer newId = newOwner == null ? null : newOwner.getId();
            if (!java.util.Objects.equals(oldId, newId)) {
                String oldName = displayUserName(piece.getOwner());
                String newName = displayUserName(newOwner);
                piece.setOwner(newOwner);
                historyService.recordOwnerChanged(piece, actor, oldName, newName);
            }
        }

        if (Boolean.TRUE.equals(dto.getClearLocation())) {
            if (piece.getLocation() != null) {
                String oldName = piece.getLocation().getName();
                piece.setLocation(null);
                historyService.recordLocationChanged(piece, actor, oldName, null);
            }
        } else if (dto.getLocationId() != null) {
            Location newLoc = resolveLocation(org, dto.getLocationId(), true);
            Integer oldId = piece.getLocation() == null ? null : piece.getLocation().getId();
            Integer newId = newLoc == null ? null : newLoc.getId();
            if (!java.util.Objects.equals(oldId, newId)) {
                String oldName = piece.getLocation() == null ? null : piece.getLocation().getName();
                String newName = newLoc == null ? null : newLoc.getName();
                piece.setLocation(newLoc);
                historyService.recordLocationChanged(piece, actor, oldName, newName);
            }
        }

        if (Boolean.TRUE.equals(dto.getClearCover())) {
            applyCoverChange(piece, null, actor);
        } else if (dto.getCoverAttachmentId() != null) {
            PieceAttachment cover = resolveCoverAttachment(piece, dto.getCoverAttachmentId());
            applyCoverChange(piece, cover, actor);
        }

        Map<Integer, PieceTypeAttribute> typePool = collectTypeAttributePool(piece.getPieceTypes());
        Map<Integer, OrganizationPieceAttribute> orgPool = collectOrgAttributePool(org);
        Map<Integer, PieceAttributeValue> currentTypeValues = new HashMap<>();
        for (PieceAttributeValue v : valueRepository.findByPiece(piece)) {
            currentTypeValues.put(v.getAttribute().getId(), v);
        }
        Map<Integer, PieceOrganizationAttributeValue> currentOrgValues = new HashMap<>();
        for (PieceOrganizationAttributeValue v : orgValueRepository.findByPiece(piece)) {
            currentOrgValues.put(v.getAttribute().getId(), v);
        }

        if (dto.getAttributeValues() != null) {
            for (AttributeValueInputDto input : dto.getAttributeValues()) {
                if (input.effectiveScope() == AttributeScope.ORG) {
                    OrganizationPieceAttribute attribute = requireOrgAttributeInPool(orgPool, input.attributeId());
                    String normalized = validationRegistry.validate(
                            attribute.getType(), attribute.getValidatorsJson(),
                            attribute.isRequired(), input.value());
                    PieceOrganizationAttributeValue existing = currentOrgValues.get(attribute.getId());
                    String oldValue = existing == null ? null : existing.getValue();
                    if (java.util.Objects.equals(oldValue, normalized)) continue;
                    if (normalized == null) {
                        if (existing != null) {
                            orgValueRepository.delete(existing);
                            currentOrgValues.remove(attribute.getId());
                        }
                    } else if (existing == null) {
                        PieceOrganizationAttributeValue created = new PieceOrganizationAttributeValue()
                                .setPiece(piece)
                                .setAttribute(attribute)
                                .setValue(normalized);
                        currentOrgValues.put(attribute.getId(), orgValueRepository.save(created));
                    } else {
                        existing.setValue(normalized);
                        orgValueRepository.save(existing);
                    }
                    historyService.recordAttributeValueChanged(piece, actor,
                            "org:" + attribute.getName(), oldValue, normalized);
                } else {
                    PieceTypeAttribute attribute = requireAttributeInPool(typePool, input.attributeId());
                    String normalized = validationRegistry.validate(attribute, input.value());
                    PieceAttributeValue existing = currentTypeValues.get(attribute.getId());
                    String oldValue = existing == null ? null : existing.getValue();
                    if (java.util.Objects.equals(oldValue, normalized)) continue;
                    if (normalized == null) {
                        if (existing != null) {
                            valueRepository.delete(existing);
                            currentTypeValues.remove(attribute.getId());
                        }
                    } else if (existing == null) {
                        PieceAttributeValue created = new PieceAttributeValue()
                                .setPiece(piece)
                                .setAttribute(attribute)
                                .setValue(normalized);
                        currentTypeValues.put(attribute.getId(), valueRepository.save(created));
                    } else {
                        existing.setValue(normalized);
                        valueRepository.save(existing);
                    }
                    historyService.recordAttributeValueChanged(piece, actor,
                            attribute.getName(), oldValue, normalized);
                }
            }
        }

        PieceStatus newStatus = statusCalculator.compute(
                typePool.values(), currentTypeValues, orgPool.values(), currentOrgValues);
        if (newStatus != oldStatus) {
            piece.setStatus(newStatus);
            historyService.recordStatusChanged(piece, actor, oldStatus, newStatus);
        }
        return pieceRepository.save(piece);
    }

    @Transactional
    public void softDelete(Integer orgId, Integer pieceId) {
        Piece piece = findInOrg(orgId, pieceId);
        User actor = currentUser();
        piece.setDeletedAt(LocalDateTime.now());
        pieceRepository.save(piece);
        historyService.recordDeleted(piece, actor);
    }

    /**
     * Bulk recompute the status of every piece that includes {@code type} in its type set after
     * the type's schema changed. Each piece's status is based on the union of all its types'
     * required attributes plus the organization-level required attributes. Records
     * {@code STATUS_CHANGED} entries only for pieces that actually moved.
     */
    @Transactional
    public void recalcStatusForType(PieceType type) {
        List<Piece> pieces = pieceRepository.findByPieceTypesContaining(type);
        recalcStatusFor(pieces, type.getOrganization());
    }

    /**
     * Bulk recompute the status of every non-deleted piece in {@code organization}. Triggered when
     * an organization-level attribute is added, modified or soft-deleted.
     */
    @Transactional
    public void recalcStatusForOrganization(Organization organization) {
        List<Piece> pieces = pieceRepository.findByOrganization(organization);
        recalcStatusFor(pieces, organization);
    }

    /**
     * Drops every {@code piece_organization_attribute_values} row referencing the given
     * organization-level attribute. Today this is a no-op kept for parity with the type-level
     * lifecycle (soft-delete preserves values); kept private until the policy demands it.
     */
    @Transactional
    public int removeOrgValuesForAttribute(OrganizationPieceAttribute attribute) {
        return orgValueRepository.deleteByAttribute(attribute);
    }

    private void recalcStatusFor(List<Piece> pieces, Organization organization) {
        if (pieces.isEmpty()) return;
        List<PieceAttributeValue> typeAll = valueRepository.findByPieceIn(pieces);
        Map<Integer, Map<Integer, PieceAttributeValue>> typeByPiece = new LinkedHashMap<>();
        for (PieceAttributeValue v : typeAll) {
            typeByPiece.computeIfAbsent(v.getPiece().getId(), k -> new HashMap<>())
                    .put(v.getAttribute().getId(), v);
        }
        List<PieceOrganizationAttributeValue> orgAll = orgValueRepository.findByPieceIn(pieces);
        Map<Integer, Map<Integer, PieceOrganizationAttributeValue>> orgByPiece = new LinkedHashMap<>();
        for (PieceOrganizationAttributeValue v : orgAll) {
            orgByPiece.computeIfAbsent(v.getPiece().getId(), k -> new HashMap<>())
                    .put(v.getAttribute().getId(), v);
        }
        Map<Integer, OrganizationPieceAttribute> orgPool = collectOrgAttributePool(organization);
        User actor = currentUser();
        for (Piece p : pieces) {
            Map<Integer, PieceTypeAttribute> typePool = collectTypeAttributePool(p.getPieceTypes());
            Map<Integer, PieceAttributeValue> typeValues = typeByPiece.getOrDefault(p.getId(), Map.of());
            Map<Integer, PieceOrganizationAttributeValue> orgValues = orgByPiece.getOrDefault(p.getId(), Map.of());
            PieceStatus newStatus = statusCalculator.compute(
                    typePool.values(), typeValues, orgPool.values(), orgValues);
            if (newStatus != p.getStatus()) {
                PieceStatus oldStatus = p.getStatus();
                p.setStatus(newStatus);
                pieceRepository.save(p);
                historyService.recordStatusChanged(p, actor, oldStatus, newStatus);
            }
        }
    }

    /**
     * Resolves a list of type ids, all in {@code orgId}, into a deduplicated set in caller-provided
     * order (LinkedHashSet). A piece may have zero, one or many types: {@code null} or empty input
     * produces an empty result and is allowed. Unknown ids produce 400; null elements inside the
     * list are also rejected.
     */
    private Set<PieceType> resolveTypes(Integer orgId, List<Integer> typeIds) {
        if (typeIds == null || typeIds.isEmpty()) {
            return new LinkedHashSet<>();
        }
        Set<Integer> seen = new LinkedHashSet<>();
        Set<PieceType> resolved = new LinkedHashSet<>();
        for (Integer id : typeIds) {
            if (id == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "El identificador del tipo no puede ser nulo");
            }
            if (!seen.add(id)) continue;
            resolved.add(pieceTypeService.findInOrg(orgId, id));
        }
        return resolved;
    }

    /**
     * Replaces the piece's set of types with {@code newTypeIds}, removing attribute values whose
     * attribute belonged exclusively to a type that is no longer attached. Records the change in
     * the history when something actually moved.
     */
    private void applyTypesUpdate(Integer orgId, Piece piece, List<Integer> newTypeIds, User actor) {
        Set<PieceType> newTypes = resolveTypes(orgId, newTypeIds);
        Set<Integer> oldIds = piece.getPieceTypes().stream()
                .map(PieceType::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Integer> nextIds = newTypes.stream()
                .map(PieceType::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (oldIds.equals(nextIds)) {
            return;
        }
        String oldNames = formatTypeNames(piece.getPieceTypes());
        piece.setPieceTypes(newTypes);
        pieceRepository.save(piece);

        // Drop values whose attribute is no longer covered by any of the new types.
        Map<Integer, PieceTypeAttribute> retainedPool = collectTypeAttributePool(newTypes);
        for (PieceAttributeValue v : valueRepository.findByPiece(piece)) {
            if (!retainedPool.containsKey(v.getAttribute().getId())) {
                valueRepository.delete(v);
            }
        }
        historyService.recordPieceTypesChanged(piece, actor, oldNames, formatTypeNames(newTypes));
    }

    /**
     * Builds the union of type-level attributes across {@code types} keyed by attribute id. When
     * the same attribute id appears in multiple types (impossible with the current schema but
     * coded defensively) the first occurrence wins.
     */
    private Map<Integer, PieceTypeAttribute> collectTypeAttributePool(Set<PieceType> types) {
        Map<Integer, PieceTypeAttribute> pool = new LinkedHashMap<>();
        for (PieceType t : types) {
            for (PieceTypeAttribute attr : pieceTypeService.attributesOf(t)) {
                pool.putIfAbsent(attr.getId(), attr);
            }
        }
        return pool;
    }

    /**
     * Builds the organization-level attribute pool keyed by attribute id, in the order
     * determined by {@link OrganizationPieceAttribute#getPosition()}.
     */
    private Map<Integer, OrganizationPieceAttribute> collectOrgAttributePool(Organization organization) {
        Map<Integer, OrganizationPieceAttribute> pool = new LinkedHashMap<>();
        for (OrganizationPieceAttribute attr :
                orgAttributeRepository.findByOrganizationOrderByPositionAscIdAsc(organization)) {
            pool.put(attr.getId(), attr);
        }
        return pool;
    }

    private PieceTypeAttribute requireAttributeInPool(Map<Integer, PieceTypeAttribute> pool, Integer attributeId) {
        if (attributeId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Falta el id del atributo en uno de los valores");
        }
        PieceTypeAttribute attr = pool.get(attributeId);
        if (attr == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El atributo " + attributeId + " no pertenece a ninguno de los tipos del artículo");
        }
        return attr;
    }

    private OrganizationPieceAttribute requireOrgAttributeInPool(
            Map<Integer, OrganizationPieceAttribute> pool, Integer attributeId) {
        if (attributeId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Falta el id del atributo en uno de los valores");
        }
        OrganizationPieceAttribute attr = pool.get(attributeId);
        if (attr == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El atributo de organización " + attributeId + " no pertenece a esta organización");
        }
        return attr;
    }

    private String formatTypeNames(Set<PieceType> types) {
        return types.stream()
                .map(PieceType::getName)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .collect(Collectors.joining(", "));
    }

    private Location resolveLocation(Organization org, Integer locationId, boolean allowNullForUpdate) {
        if (locationId == null) {
            return null;
        }
        Location loc = locationRepository.findById(locationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ubicación no encontrada"));
        if (!loc.getOrganization().getId().equals(org.getId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La ubicación pertenece a otra organización");
        }
        return loc;
    }

    private User resolveOwner(Organization org, Integer ownerUserId, boolean allowNullForUpdate) {
        if (ownerUserId == null) {
            return null;
        }
        User user = userRepository.findById(ownerUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "El propietario no existe"));
        Optional<?> membership = memberRepository.findByUserAndOrganization(user, org);
        if (membership.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El propietario debe ser miembro de la organización");
        }
        return user;
    }

    private String sanitizeName(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El nombre del artículo es obligatorio");
        }
        String trimmed = raw.trim();
        if (trimmed.length() > MAX_NAME_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El nombre no puede superar " + MAX_NAME_LENGTH + " caracteres");
        }
        return trimmed;
    }

    private String sanitizeSerialNumber(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.length() > MAX_SERIAL_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El número de serie no puede superar " + MAX_SERIAL_LENGTH + " caracteres");
        }
        return trimmed;
    }

    /**
     * Enforces uniqueness of {@code serialNumber} within {@code organizationId} (case-sensitive).
     * Skips the check when the value is {@code null}. When {@code excludePieceId} is non-null the
     * piece with that id is ignored — used during update so resaving the same value does not
     * collide with itself.
     */
    private void ensureSerialNumberUnique(Integer organizationId, String serialNumber, Integer excludePieceId) {
        if (serialNumber == null) return;
        boolean clash = excludePieceId == null
                ? pieceRepository.existsByOrganization_IdAndSerialNumber(organizationId, serialNumber)
                : pieceRepository.existsByOrganization_IdAndSerialNumberAndIdNot(
                        organizationId, serialNumber, excludePieceId);
        if (clash) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ya existe otro artículo con ese número de serie");
        }
    }

    /**
     * Resolves an attachment id into the corresponding {@link PieceAttachment} after checking that
     * it belongs to {@code piece}, has {@code kind=IMAGE} and is not soft-deleted.
     */
    private PieceAttachment resolveCoverAttachment(Piece piece, Integer attachmentId) {
        PieceAttachment attachment = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "La imagen de portada no existe"));
        if (!attachment.getPiece().getId().equals(piece.getId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La imagen de portada debe pertenecer al mismo artículo");
        }
        if (attachment.getKind() != PieceAttachmentKind.IMAGE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La portada debe ser una imagen");
        }
        return attachment;
    }

    /**
     * Persists the cover change on {@code piece} when it differs from the current value, recording
     * the move in piece history as a {@code PIECE_UPDATED} entry on the {@code coverAttachmentId}
     * field.
     */
    private void applyCoverChange(Piece piece, PieceAttachment newCover, User actor) {
        Integer oldId = piece.getCoverAttachment() == null ? null : piece.getCoverAttachment().getId();
        Integer newId = newCover == null ? null : newCover.getId();
        if (java.util.Objects.equals(oldId, newId)) return;
        piece.setCoverAttachment(newCover);
        historyService.recordUpdated(piece, actor, "coverAttachmentId",
                oldId == null ? null : oldId.toString(),
                newId == null ? null : newId.toString());
    }

    private void ensureUnderPiecesPerOrgQuota(Organization organization) {
        long current = pieceRepository.countByOrganization(organization);
        long max = quotas.getMaxPiecesPerOrg();
        if (current >= max) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    ErrorCodes.ORGANIZATIONS_QUOTA_EXCEEDED,
                    Map.of("limit", "max_pieces_per_org", "max", max, "current", current));
        }
    }

    private String emptyToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private Pageable boundPageable(Pageable pageable) {
        if (pageable.getPageSize() > MAX_PAGE_SIZE) {
            return org.springframework.data.domain.PageRequest.of(
                    pageable.getPageNumber(), MAX_PAGE_SIZE, pageable.getSort());
        }
        return pageable;
    }

    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof User user)) {
            return null;
        }
        return user;
    }

    /**
     * Returns the human-readable display name for {@code user} ({@code "First Last"}), falling
     * back to the e-mail when both name fields are blank, or {@code null} for a {@code null}
     * user. Used to write a stable, human-friendly value into the audit log so the entry stays
     * meaningful after the user is renamed or removed.
     *
     * @param user the user whose display name to resolve
     * @return the display name, the e-mail as fallback, or {@code null} if {@code user} is null
     */
    private static String displayUserName(User user) {
        if (user == null) return null;
        String first = user.getName();
        String last = user.getLastName();
        String full = ((first == null ? "" : first) + " " + (last == null ? "" : last)).trim();
        if (!full.isEmpty()) return full;
        return user.getEmail();
    }
}
