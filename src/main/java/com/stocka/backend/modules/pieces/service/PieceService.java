package com.stocka.backend.modules.pieces.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.stocka.backend.modules.locations.entity.Location;
import com.stocka.backend.modules.locations.repository.LocationRepository;
import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.organizations.repository.OrganizationMemberRepository;
import com.stocka.backend.modules.organizations.service.OrganizationService;
import com.stocka.backend.modules.pieces.dto.AttributeValueInputDto;
import com.stocka.backend.modules.pieces.dto.CreatePieceDto;
import com.stocka.backend.modules.pieces.dto.UpdatePieceDto;
import com.stocka.backend.modules.pieces.entity.Piece;
import com.stocka.backend.modules.pieces.entity.PieceAttributeValue;
import com.stocka.backend.modules.pieces.entity.PieceStatus;
import com.stocka.backend.modules.pieces.repository.PieceAttributeValueRepository;
import com.stocka.backend.modules.pieces.repository.PieceRepository;
import com.stocka.backend.modules.pieces.service.attributevalidation.AttributeValueValidationRegistry;
import com.stocka.backend.modules.piecetypes.entity.PieceType;
import com.stocka.backend.modules.piecetypes.entity.PieceTypeAttribute;
import com.stocka.backend.modules.piecetypes.service.PieceTypeService;
import com.stocka.backend.modules.users.entity.User;
import com.stocka.backend.modules.users.repository.UserRepository;

import jakarta.persistence.criteria.Predicate;

/**
 * CRUD and lookup for {@link Piece}. Validates cross-org references, normalizes attribute values
 * through the strategy registry, recalculates status on every mutation, and records every
 * relevant change in the piece history.
 */
@Service
public class PieceService {
    private static final int MAX_NAME_LENGTH = 255;
    private static final int MAX_PAGE_SIZE = 100;

    private final PieceRepository pieceRepository;
    private final PieceAttributeValueRepository valueRepository;
    private final PieceStatusCalculator statusCalculator;
    private final PieceHistoryService historyService;
    private final OrganizationService organizationService;
    private final OrganizationMemberRepository memberRepository;
    private final PieceTypeService pieceTypeService;
    private final AttributeValueValidationRegistry validationRegistry;
    private final LocationRepository locationRepository;
    private final UserRepository userRepository;

    public PieceService(
            PieceRepository pieceRepository,
            PieceAttributeValueRepository valueRepository,
            PieceStatusCalculator statusCalculator,
            PieceHistoryService historyService,
            OrganizationService organizationService,
            OrganizationMemberRepository memberRepository,
            PieceTypeService pieceTypeService,
            AttributeValueValidationRegistry validationRegistry,
            LocationRepository locationRepository,
            UserRepository userRepository
    ) {
        this.pieceRepository = pieceRepository;
        this.valueRepository = valueRepository;
        this.statusCalculator = statusCalculator;
        this.historyService = historyService;
        this.organizationService = organizationService;
        this.memberRepository = memberRepository;
        this.pieceTypeService = pieceTypeService;
        this.validationRegistry = validationRegistry;
        this.locationRepository = locationRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public Piece create(Integer orgId, CreatePieceDto dto) {
        Organization org = organizationService.findById(orgId);
        if (dto.getPieceTypeId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El tipo de artículo es obligatorio");
        }
        PieceType type = pieceTypeService.findInOrg(orgId, dto.getPieceTypeId());

        String name = sanitizeName(dto.getName());
        Location location = resolveLocation(org, dto.getLocationId(), false);
        User owner = resolveOwner(org, dto.getOwnerUserId(), false);

        Piece piece = new Piece()
                .setOrganization(org)
                .setPieceType(type)
                .setName(name)
                .setDescription(emptyToNull(dto.getDescription()))
                .setLocation(location)
                .setOwner(owner)
                .setStatus(PieceStatus.PENDING);
        piece = pieceRepository.save(piece);

        List<PieceTypeAttribute> attrs = pieceTypeService.attributesOf(type);
        Map<Integer, PieceAttributeValue> persistedValues = new HashMap<>();
        if (dto.getAttributeValues() != null) {
            for (AttributeValueInputDto input : dto.getAttributeValues()) {
                PieceTypeAttribute attribute = requireAttributeOfType(attrs, input.attributeId());
                String normalized = validationRegistry.validate(attribute, input.value());
                if (normalized != null) {
                    PieceAttributeValue value = new PieceAttributeValue()
                            .setPiece(piece)
                            .setAttribute(attribute)
                            .setValue(normalized);
                    persistedValues.put(attribute.getId(), valueRepository.save(value));
                }
            }
        }

        PieceStatus status = statusCalculator.compute(attrs, persistedValues);
        piece.setStatus(status);
        piece = pieceRepository.save(piece);

        User actor = currentUser();
        historyService.recordCreated(piece, actor);
        return piece;
    }

    public Piece findInOrg(Integer orgId, Integer pieceId) {
        Organization org = organizationService.findById(orgId);
        return pieceRepository.findByIdAndOrganization(pieceId, org)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Artículo no encontrado"));
    }

    public List<PieceAttributeValue> valuesOf(Piece piece) {
        return valueRepository.findByPiece(piece);
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
            if (pieceTypeId != null) preds.add(cb.equal(root.get("pieceType").get("id"), pieceTypeId));
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
        if (dto.getDescription() != null) {
            String old = piece.getDescription();
            String value = emptyToNull(dto.getDescription());
            if (!java.util.Objects.equals(old, value)) {
                piece.setDescription(value);
                historyService.recordUpdated(piece, actor, "description", old, value);
            }
        }

        if (Boolean.TRUE.equals(dto.getClearOwner())) {
            if (piece.getOwner() != null) {
                Integer oldId = piece.getOwner().getId();
                piece.setOwner(null);
                historyService.recordOwnerChanged(piece, actor, oldId, null);
            }
        } else if (dto.getOwnerUserId() != null) {
            User newOwner = resolveOwner(org, dto.getOwnerUserId(), true);
            Integer oldId = piece.getOwner() == null ? null : piece.getOwner().getId();
            Integer newId = newOwner == null ? null : newOwner.getId();
            if (!java.util.Objects.equals(oldId, newId)) {
                piece.setOwner(newOwner);
                historyService.recordOwnerChanged(piece, actor, oldId, newId);
            }
        }

        if (Boolean.TRUE.equals(dto.getClearLocation())) {
            if (piece.getLocation() != null) {
                Integer oldId = piece.getLocation().getId();
                piece.setLocation(null);
                historyService.recordLocationChanged(piece, actor, oldId, null);
            }
        } else if (dto.getLocationId() != null) {
            Location newLoc = resolveLocation(org, dto.getLocationId(), true);
            Integer oldId = piece.getLocation() == null ? null : piece.getLocation().getId();
            Integer newId = newLoc == null ? null : newLoc.getId();
            if (!java.util.Objects.equals(oldId, newId)) {
                piece.setLocation(newLoc);
                historyService.recordLocationChanged(piece, actor, oldId, newId);
            }
        }

        List<PieceTypeAttribute> attrs = pieceTypeService.attributesOf(piece.getPieceType());
        Map<Integer, PieceAttributeValue> currentValues = new HashMap<>();
        for (PieceAttributeValue v : valueRepository.findByPiece(piece)) {
            currentValues.put(v.getAttribute().getId(), v);
        }

        if (dto.getAttributeValues() != null) {
            for (AttributeValueInputDto input : dto.getAttributeValues()) {
                PieceTypeAttribute attribute = requireAttributeOfType(attrs, input.attributeId());
                String normalized = validationRegistry.validate(attribute, input.value());
                PieceAttributeValue existing = currentValues.get(attribute.getId());
                String oldValue = existing == null ? null : existing.getValue();
                if (java.util.Objects.equals(oldValue, normalized)) {
                    continue;
                }
                if (normalized == null) {
                    if (existing != null) {
                        valueRepository.delete(existing);
                        currentValues.remove(attribute.getId());
                    }
                } else if (existing == null) {
                    PieceAttributeValue created = new PieceAttributeValue()
                            .setPiece(piece)
                            .setAttribute(attribute)
                            .setValue(normalized);
                    currentValues.put(attribute.getId(), valueRepository.save(created));
                } else {
                    existing.setValue(normalized);
                    valueRepository.save(existing);
                }
                historyService.recordAttributeValueChanged(piece, actor, attribute.getName(), oldValue, normalized);
            }
        }

        PieceStatus newStatus = statusCalculator.compute(attrs, currentValues);
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
     * Bulk recompute the status of every piece of {@code type} after the type's schema changed.
     * Records {@code STATUS_CHANGED} entries only for pieces that actually moved.
     */
    @Transactional
    public void recalcStatusForType(PieceType type) {
        List<Piece> pieces = pieceRepository.findByPieceType(type);
        if (pieces.isEmpty()) return;
        List<PieceTypeAttribute> attrs = pieceTypeService.attributesOf(type);
        List<PieceAttributeValue> all = valueRepository.findByPieceIn(pieces);
        Map<Integer, Map<Integer, PieceAttributeValue>> byPiece = new LinkedHashMap<>();
        for (PieceAttributeValue v : all) {
            byPiece.computeIfAbsent(v.getPiece().getId(), k -> new HashMap<>())
                    .put(v.getAttribute().getId(), v);
        }
        User actor = currentUser();
        for (Piece p : pieces) {
            Map<Integer, PieceAttributeValue> values = byPiece.getOrDefault(p.getId(), Map.of());
            PieceStatus newStatus = statusCalculator.compute(attrs, values);
            if (newStatus != p.getStatus()) {
                PieceStatus oldStatus = p.getStatus();
                p.setStatus(newStatus);
                pieceRepository.save(p);
                historyService.recordStatusChanged(p, actor, oldStatus, newStatus);
            }
        }
    }

    private PieceTypeAttribute requireAttributeOfType(List<PieceTypeAttribute> attrs, Integer attributeId) {
        if (attributeId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Falta el id del atributo en uno de los valores");
        }
        return attrs.stream()
                .filter(a -> attributeId.equals(a.getId()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "El atributo " + attributeId + " no pertenece al tipo del artículo"));
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
}
