package com.stocka.backend.modules.organizations.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.stocka.backend.modules.organizations.dto.CreateOrganizationPieceAttributeDto;
import com.stocka.backend.modules.organizations.dto.UpdateOrganizationPieceAttributeDto;
import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.organizations.entity.OrganizationPieceAttribute;
import com.stocka.backend.modules.organizations.repository.OrganizationPieceAttributeRepository;
import com.stocka.backend.modules.pieces.repository.PieceOrganizationAttributeValueRepository;
import com.stocka.backend.modules.piecetypes.entity.AttributeType;
import com.stocka.backend.modules.piecetypes.service.PieceTypeService;
import com.stocka.backend.modules.piecetypes.service.ValidatorsJsonCodec;
import com.stocka.backend.modules.sync.support.SyncStamper;

/**
 * CRUD for {@link OrganizationPieceAttribute}. Mirrors the behavior of
 * {@link com.stocka.backend.modules.piecetypes.service.PieceTypeAttributeService} but applies to
 * every piece in the organization. Reuses the validation helpers
 * {@link PieceTypeService#validateAttributeName(String)} and
 * {@link PieceTypeService#validateValidators(AttributeType,
 * com.stocka.backend.modules.piecetypes.dto.AttributeValidatorsDto)}.
 *
 * <p>Soft-deleting an attribute does <em>not</em> remove its stored values: the same name can later
 * be restored without losing per-piece data. This matches the policy chosen for type-level
 * attributes.
 */
@Service
public class OrganizationPieceAttributeService {
    private static final int MAX_DISPLAY_NAME_LENGTH = 160;

    private final OrganizationPieceAttributeRepository attributeRepository;
    private final OrganizationService organizationService;
    private final ValidatorsJsonCodec validatorsCodec;
    private final PieceOrganizationAttributeValueRepository valueRepository;
    private final Optional<OrganizationPieceAttributeUsage> usage;
    private final SyncStamper syncStamper;

    public OrganizationPieceAttributeService(
            OrganizationPieceAttributeRepository attributeRepository,
            OrganizationService organizationService,
            ValidatorsJsonCodec validatorsCodec,
            PieceOrganizationAttributeValueRepository valueRepository,
            Optional<OrganizationPieceAttributeUsage> usage,
            SyncStamper syncStamper
    ) {
        this.attributeRepository = attributeRepository;
        this.organizationService = organizationService;
        this.validatorsCodec = validatorsCodec;
        this.valueRepository = valueRepository;
        this.usage = usage;
        this.syncStamper = syncStamper;
    }

    public List<OrganizationPieceAttribute> listAll(Integer orgId) {
        Organization org = organizationService.findById(orgId);
        return attributeRepository.findByOrganizationOrderByPositionAscIdAsc(org);
    }

    public OrganizationPieceAttribute findInOrg(Integer orgId, Integer attributeId) {
        Organization org = organizationService.findById(orgId);
        OrganizationPieceAttribute attr = attributeRepository.findById(attributeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Atributo no encontrado"));
        if (!attr.getOrganization().getId().equals(org.getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Atributo no encontrado");
        }
        return attr;
    }

    @Transactional
    public OrganizationPieceAttribute create(Integer orgId, CreateOrganizationPieceAttributeDto dto) {
        Organization org = organizationService.findById(orgId);
        PieceTypeService.validateAttributeName(dto.getName());
        ensureUniqueName(org, dto.getName(), null);
        if (dto.getType() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El tipo de atributo es obligatorio para '" + dto.getName() + "'");
        }
        PieceTypeService.validateValidators(dto.getType(), dto.getValidators());

        int position = dto.getPosition() == null
                ? attributeRepository.findByOrganizationOrderByPositionAscIdAsc(org).size()
                : dto.getPosition();
        boolean required = dto.getRequired() == null || dto.getRequired();
        String displayName = sanitizeDisplayName(dto.getDisplayName(), dto.getName());

        OrganizationPieceAttribute attr = new OrganizationPieceAttribute()
                .setOrganization(org)
                .setName(dto.getName())
                .setDisplayName(displayName)
                .setType(dto.getType())
                .setRequired(required)
                .setPosition(position)
                .setValidatorsJson(validatorsCodec.serialize(dto.getValidators()));
        syncStamper.stamp(attr);
        OrganizationPieceAttribute saved = attributeRepository.save(attr);
        if (required) {
            usage.ifPresent(u -> u.recalcStatusForOrganization(org));
        }
        return saved;
    }

    @Transactional
    public OrganizationPieceAttribute update(Integer orgId, Integer attributeId,
                                             UpdateOrganizationPieceAttributeDto dto) {
        OrganizationPieceAttribute attr = findInOrg(orgId, attributeId);
        boolean affectsStatus = false;

        if (dto.getName() != null) {
            String trimmed = dto.getName().trim();
            if (!trimmed.equals(attr.getName())) {
                PieceTypeService.validateAttributeName(trimmed);
                ensureUniqueName(attr.getOrganization(), trimmed, attr.getId());
                attr.setName(trimmed);
            }
        }
        if (dto.getType() != null && dto.getType() != attr.getType()) {
            long valueCount = valueRepository.countByAttribute(attr);
            if (valueCount > 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "No se puede cambiar el tipo: existen " + valueCount
                                + " piezas con valores guardados. Borra los valores antes de continuar.");
            }
            attr.setType(dto.getType());
            if (dto.getValidators() == null) {
                attr.setValidatorsJson(null);
            }
            affectsStatus = true;
        }
        if (dto.getDisplayName() != null) {
            String trimmed = dto.getDisplayName().trim();
            if (trimmed.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "El nombre visible no puede estar vacío");
            }
            if (trimmed.length() > MAX_DISPLAY_NAME_LENGTH) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "El nombre visible no puede superar " + MAX_DISPLAY_NAME_LENGTH + " caracteres");
            }
            attr.setDisplayName(trimmed);
        }
        if (dto.getRequired() != null && dto.getRequired() != attr.isRequired()) {
            attr.setRequired(dto.getRequired());
            affectsStatus = true;
        }
        if (dto.getPosition() != null) {
            attr.setPosition(dto.getPosition());
        }
        if (dto.getValidators() != null) {
            AttributeType effectiveType = attr.getType();
            PieceTypeService.validateValidators(effectiveType, dto.getValidators());
            attr.setValidatorsJson(validatorsCodec.serialize(dto.getValidators()));
            affectsStatus = true;
        }
        syncStamper.stamp(attr);
        OrganizationPieceAttribute saved = attributeRepository.save(attr);
        if (affectsStatus) {
            usage.ifPresent(u -> u.recalcStatusForOrganization(saved.getOrganization()));
        }
        return saved;
    }

    @Transactional
    public void softDelete(Integer orgId, Integer attributeId) {
        OrganizationPieceAttribute attr = findInOrg(orgId, attributeId);
        usage.ifPresent(u -> u.removeValuesForAttribute(attr));
        attr.setDeletedAt(LocalDateTime.now());
        syncStamper.stamp(attr);
        attributeRepository.save(attr);
        usage.ifPresent(u -> u.recalcStatusForOrganization(attr.getOrganization()));
    }

    private void ensureUniqueName(Organization org, String name, Integer excludeId) {
        if (name == null) return;
        Optional<OrganizationPieceAttribute> existing =
                attributeRepository.findByOrganizationAndName(org, name);
        if (existing.isPresent() && (excludeId == null || !existing.get().getId().equals(excludeId))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ya existe un atributo de organización con el nombre '" + name + "'");
        }
    }

    private String sanitizeDisplayName(String raw, String fallback) {
        String value = (raw == null || raw.isBlank()) ? fallback : raw.trim();
        if (value.length() > MAX_DISPLAY_NAME_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El nombre visible no puede superar " + MAX_DISPLAY_NAME_LENGTH + " caracteres");
        }
        return value;
    }
}
