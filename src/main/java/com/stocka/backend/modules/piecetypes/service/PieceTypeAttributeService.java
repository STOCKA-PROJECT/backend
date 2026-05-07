package com.stocka.backend.modules.piecetypes.service;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.stocka.backend.modules.common.error.ApiException;
import com.stocka.backend.modules.common.error.ErrorCodes;
import com.stocka.backend.modules.piecetypes.dto.UpdatePieceTypeAttributeDto;
import com.stocka.backend.modules.piecetypes.entity.AttributeType;
import com.stocka.backend.modules.piecetypes.entity.PieceType;
import com.stocka.backend.modules.piecetypes.entity.PieceTypeAttribute;
import com.stocka.backend.modules.piecetypes.repository.PieceTypeAttributeRepository;
import com.stocka.backend.modules.pieces.repository.PieceAttributeValueRepository;

/**
 * Mutations on individual attributes of a piece type. Covers updates (technical name, display
 * name, type, required, position, validators) and soft-deletion. Each operation that affects
 * whether a piece is considered complete triggers a status recalc through {@link PieceTypeUsage}.
 *
 * <p>Renaming preserves stored values (FK is by id) and does not rewrite past
 * {@link com.stocka.backend.modules.pieces.entity.PieceHistory} entries. Changing the {@code type}
 * is rejected with HTTP 409 if any active value exists for the attribute, since the stored values
 * may not be valid under the new type.
 */
@Service
public class PieceTypeAttributeService {
    private final PieceTypeService pieceTypeService;
    private final PieceTypeAttributeRepository attributeRepository;
    private final PieceAttributeValueRepository valueRepository;
    private final ValidatorsJsonCodec validatorsCodec;
    private final Optional<PieceTypeUsage> usage;

    public PieceTypeAttributeService(
            PieceTypeService pieceTypeService,
            PieceTypeAttributeRepository attributeRepository,
            PieceAttributeValueRepository valueRepository,
            ValidatorsJsonCodec validatorsCodec,
            Optional<PieceTypeUsage> usage
    ) {
        this.pieceTypeService = pieceTypeService;
        this.attributeRepository = attributeRepository;
        this.valueRepository = valueRepository;
        this.validatorsCodec = validatorsCodec;
        this.usage = usage;
    }

    public PieceTypeAttribute findInOrg(Integer orgId, Integer typeId, Integer attributeId) {
        PieceType type = pieceTypeService.findInOrg(orgId, typeId);
        PieceTypeAttribute attr = attributeRepository.findById(attributeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Atributo no encontrado"));
        if (!attr.getPieceType().getId().equals(type.getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Atributo no encontrado");
        }
        return attr;
    }

    @Transactional
    public PieceTypeAttribute update(Integer orgId, Integer typeId, Integer attributeId, UpdatePieceTypeAttributeDto dto) {
        PieceTypeAttribute attr = findInOrg(orgId, typeId, attributeId);
        boolean affectsStatus = false;

        if (dto.getName() != null) {
            String trimmed = dto.getName().trim();
            if (!trimmed.equals(attr.getName())) {
                PieceTypeService.validateAttributeName(trimmed);
                pieceTypeService.ensureUniqueAttributeName(attr.getPieceType(), trimmed, attr.getId());
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
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El nombre visible no puede estar vacío");
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

        PieceTypeAttribute saved = saveAndFlush(attr);
        if (affectsStatus) {
            usage.ifPresent(u -> u.recalcStatusForType(saved.getPieceType()));
        }
        return saved;
    }

    @Transactional
    public void softDelete(Integer orgId, Integer typeId, Integer attributeId) {
        PieceTypeAttribute attr = findInOrg(orgId, typeId, attributeId);
        usage.ifPresent(u -> u.removeValuesForAttribute(attr));
        // Free the (piece_type_id, name) slot so a fresh attribute with the same technical name
        // can be added later without colliding with the uk_piece_type_attr_type_name UNIQUE,
        // which covers all rows regardless of deleted_at.
        attr.setName(PieceTypeService.buildSoftDeletedName(
                attr.getName(), attr.getId(), PieceTypeService.MAX_ATTR_NAME_LENGTH));
        attr.setDeletedAt(LocalDateTime.now());
        attributeRepository.save(attr);
        usage.ifPresent(u -> u.recalcStatusForType(attr.getPieceType()));
    }

    private PieceTypeAttribute saveAndFlush(PieceTypeAttribute attr) {
        try {
            return attributeRepository.saveAndFlush(attr);
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.PIECE_TYPES_ATTRIBUTE_NAME_CONFLICT, null, ex);
        }
    }
}
