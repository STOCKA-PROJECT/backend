package com.stocka.backend.modules.piecetypes.service;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.stocka.backend.modules.common.error.ApiException;
import com.stocka.backend.modules.common.error.ErrorCodes;
import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.organizations.service.OrganizationService;
import com.stocka.backend.modules.piecetypes.dto.AttributeValidatorsDto;
import com.stocka.backend.modules.piecetypes.dto.CreatePieceTypeAttributeDto;
import com.stocka.backend.modules.piecetypes.dto.CreatePieceTypeDto;
import com.stocka.backend.modules.piecetypes.dto.UpdatePieceTypeDto;
import com.stocka.backend.modules.piecetypes.entity.AttributeType;
import com.stocka.backend.modules.piecetypes.entity.PieceType;
import com.stocka.backend.modules.piecetypes.entity.PieceTypeAttribute;
import com.stocka.backend.modules.piecetypes.repository.PieceTypeAttributeRepository;
import com.stocka.backend.modules.piecetypes.repository.PieceTypeRepository;

/**
 * CRUD for {@link PieceType} entities and their initial attribute set. Attribute mutations
 * post-creation live in {@link PieceTypeAttributeService}.
 */
@Service
public class PieceTypeService {
    static final Pattern ATTR_NAME_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{0,79}$");
    static final int MAX_TYPE_NAME_LENGTH = 120;
    static final int MAX_ATTR_NAME_LENGTH = 80;
    private static final int MAX_DISPLAY_NAME_LENGTH = 160;
    private static final String SOFT_DELETE_SUFFIX = "::deleted::";

    private final PieceTypeRepository pieceTypeRepository;
    private final PieceTypeAttributeRepository attributeRepository;
    private final OrganizationService organizationService;
    private final ValidatorsJsonCodec validatorsCodec;
    private final Optional<PieceTypeUsage> usage;

    public PieceTypeService(
            PieceTypeRepository pieceTypeRepository,
            PieceTypeAttributeRepository attributeRepository,
            OrganizationService organizationService,
            ValidatorsJsonCodec validatorsCodec,
            Optional<PieceTypeUsage> usage
    ) {
        this.pieceTypeRepository = pieceTypeRepository;
        this.attributeRepository = attributeRepository;
        this.organizationService = organizationService;
        this.validatorsCodec = validatorsCodec;
        this.usage = usage;
    }

    @Transactional
    public PieceType create(Integer orgId, CreatePieceTypeDto dto) {
        Organization org = organizationService.findById(orgId);
        String name = sanitizeTypeName(dto.getName());
        ensureUniqueTypeName(org, name, null);

        PieceType type = new PieceType()
                .setOrganization(org)
                .setName(name);
        type = saveTypeAndFlush(type);

        if (dto.getAttributes() != null) {
            ensureUniqueAttributeNamesInPayload(dto.getAttributes());
            int idx = 0;
            for (CreatePieceTypeAttributeDto attrDto : dto.getAttributes()) {
                buildAttribute(type, attrDto, attrDto.getPosition() == null ? idx : attrDto.getPosition());
                idx++;
            }
        }
        return type;
    }

    public List<PieceType> listAll(Integer orgId) {
        Organization org = organizationService.findById(orgId);
        return pieceTypeRepository.findByOrganization(org);
    }

    public PieceType findInOrg(Integer orgId, Integer typeId) {
        Organization org = organizationService.findById(orgId);
        PieceType type = pieceTypeRepository.findById(typeId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.PIECE_TYPES_NOT_FOUND));
        if (!type.getOrganization().getId().equals(org.getId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.PIECE_TYPES_NOT_FOUND);
        }
        return type;
    }

    public List<PieceTypeAttribute> attributesOf(PieceType type) {
        return attributeRepository.findByPieceTypeOrderByPositionAscIdAsc(type);
    }

    @Transactional
    public PieceType update(Integer orgId, Integer typeId, UpdatePieceTypeDto dto) {
        PieceType type = findInOrg(orgId, typeId);
        if (dto.getName() != null) {
            String newName = sanitizeTypeName(dto.getName());
            if (!newName.equals(type.getName())) {
                ensureUniqueTypeName(type.getOrganization(), newName, type.getId());
                type.setName(newName);
            }
        }
        return saveTypeAndFlush(type);
    }

    @Transactional
    public void softDelete(Integer orgId, Integer typeId) {
        PieceType type = findInOrg(orgId, typeId);
        if (usage.isPresent()) {
            long count = usage.get().countPiecesOfType(type);
            if (count > 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "El tipo tiene " + count + " artículos; elimínalos antes de eliminar el tipo");
            }
        }
        // Free up the name so a new active type with the same name can be created. The DB-level
        // uk_piece_type_org_name UNIQUE constraint covers (organization_id, name) regardless of
        // deleted_at; appending an id-based suffix guarantees uniqueness across soft-deleted rows
        // without breaking same-org enforcement on active rows.
        type.setName(buildSoftDeletedName(type.getName(), type.getId(), MAX_TYPE_NAME_LENGTH));
        type.setDeletedAt(LocalDateTime.now());
        pieceTypeRepository.save(type);
    }

    @Transactional
    public PieceTypeAttribute addAttribute(Integer orgId, Integer typeId, CreatePieceTypeAttributeDto dto) {
        PieceType type = findInOrg(orgId, typeId);
        ensureUniqueAttributeName(type, dto.getName(), null);
        int defaultPosition = (int) attributeRepository.findByPieceTypeOrderByPositionAscIdAsc(type).size();
        PieceTypeAttribute attr = buildAttribute(type, dto, dto.getPosition() == null ? defaultPosition : dto.getPosition());
        boolean recalcNeeded = attr.isRequired();
        if (recalcNeeded) {
            usage.ifPresent(u -> u.recalcStatusForType(type));
        }
        return attr;
    }

    private PieceTypeAttribute buildAttribute(PieceType type, CreatePieceTypeAttributeDto dto, int position) {
        validateAttributeName(dto.getName());
        if (dto.getType() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El tipo de atributo es obligatorio para '" + dto.getName() + "'");
        }
        validateValidators(dto.getType(), dto.getValidators());
        String displayName = sanitizeDisplayName(dto.getDisplayName(), dto.getName());

        PieceTypeAttribute attr = new PieceTypeAttribute()
                .setPieceType(type)
                .setName(dto.getName())
                .setDisplayName(displayName)
                .setType(dto.getType())
                .setRequired(dto.getRequired() == null || dto.getRequired())
                .setPosition(position)
                .setValidatorsJson(validatorsCodec.serialize(dto.getValidators()));
        return saveAttributeAndFlush(attr);
    }

    private void ensureUniqueAttributeNamesInPayload(List<CreatePieceTypeAttributeDto> attributes) {
        Set<String> seen = new HashSet<>();
        for (CreatePieceTypeAttributeDto a : attributes) {
            if (a.getName() == null || !seen.add(a.getName())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Hay nombres de atributo duplicados en el tipo: " + a.getName());
            }
        }
    }

    void ensureUniqueAttributeName(PieceType type, String name, Integer excludeId) {
        if (name == null) return;
        Optional<PieceTypeAttribute> existing = attributeRepository.findByPieceTypeAndName(type, name);
        if (existing.isPresent() && (excludeId == null || !existing.get().getId().equals(excludeId))) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.PIECE_TYPES_ATTRIBUTE_NAME_CONFLICT);
        }
    }

    private void ensureUniqueTypeName(Organization org, String name, Integer excludeId) {
        Optional<PieceType> existing = pieceTypeRepository.findByOrganizationAndName(org, name);
        if (existing.isPresent() && (excludeId == null || !existing.get().getId().equals(excludeId))) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.PIECE_TYPES_NAME_CONFLICT);
        }
    }

    private PieceType saveTypeAndFlush(PieceType type) {
        try {
            return pieceTypeRepository.saveAndFlush(type);
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.PIECE_TYPES_NAME_CONFLICT, null, ex);
        }
    }

    private PieceTypeAttribute saveAttributeAndFlush(PieceTypeAttribute attr) {
        try {
            return attributeRepository.saveAndFlush(attr);
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.PIECE_TYPES_ATTRIBUTE_NAME_CONFLICT, null, ex);
        }
    }

    /**
     * Builds the renamed value for a soft-deleted row so its original name is freed for a new
     * active row with the same name. The id-based suffix guarantees the renamed value stays
     * unique across all previously soft-deleted rows.
     *
     * @param currentName the live name about to be released
     * @param id          primary key of the row being soft-deleted
     * @param maxLength   maximum length allowed by the column
     * @return the value to store in {@code name} alongside {@code deleted_at}
     */
    static String buildSoftDeletedName(String currentName, Integer id, int maxLength) {
        String suffix = SOFT_DELETE_SUFFIX + (id == null ? "?" : id);
        int maxBaseLen = Math.max(0, maxLength - suffix.length());
        String base = currentName == null ? "" : currentName;
        if (base.length() > maxBaseLen) {
            base = base.substring(0, maxBaseLen);
        }
        return base + suffix;
    }

    /**
     * Validates that {@code name} matches the canonical attribute identifier shape (lowercase
     * letter followed by up to 79 lowercase letters, digits or underscores). Public so the
     * organization-level attribute service can reuse the same rule.
     *
     * @param name attribute identifier proposed by the caller
     * @throws ResponseStatusException with status 400 when the name is missing or malformed
     */
    public static void validateAttributeName(String name) {
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El nombre del atributo es obligatorio");
        }
        if (!ATTR_NAME_PATTERN.matcher(name).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El nombre del atributo debe empezar por una letra minúscula y solo contener letras, dígitos y _");
        }
    }

    private String sanitizeTypeName(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El nombre del tipo es obligatorio");
        }
        String trimmed = raw.trim();
        if (trimmed.length() > MAX_TYPE_NAME_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El nombre del tipo no puede superar " + MAX_TYPE_NAME_LENGTH + " caracteres");
        }
        return trimmed;
    }

    private String sanitizeDisplayName(String raw, String fallback) {
        String value = (raw == null || raw.isBlank()) ? fallback : raw.trim();
        if (value.length() > MAX_DISPLAY_NAME_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El nombre visible no puede superar " + MAX_DISPLAY_NAME_LENGTH + " caracteres");
        }
        return value;
    }

    /**
     * Validates the type-specific validator blob attached to an attribute (option list for
     * SELECT/MULTI_SELECT, length bounds for textual types, ...). Shared with the
     * organization-level attribute service.
     *
     * @param type  declared attribute type
     * @param rules validator blob; may be {@code null} when no rules apply
     * @throws ResponseStatusException with status 400 when the rules are inconsistent or missing
     *                                 required fields for the given {@code type}
     */
    public static void validateValidators(AttributeType type, AttributeValidatorsDto rules) {
        if (type == AttributeType.SELECT || type == AttributeType.MULTI_SELECT) {
            List<String> options = rules == null ? null : rules.getOptions();
            if (options == null || options.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Los atributos de tipo " + type + " requieren una lista de opciones");
            }
            Set<String> dedup = new HashSet<>(options);
            if (dedup.size() != options.size()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Las opciones del atributo no pueden repetirse");
            }
        }
        if (rules != null && rules.getMinLength() != null && rules.getMinLength() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "minLength debe ser >= 0");
        }
        if (rules != null && rules.getMaxLength() != null && rules.getMaxLength() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "maxLength debe ser >= 0");
        }
        if (rules != null && rules.getMinLength() != null && rules.getMaxLength() != null
                && rules.getMinLength() > rules.getMaxLength()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "minLength no puede ser mayor que maxLength");
        }
    }

}
