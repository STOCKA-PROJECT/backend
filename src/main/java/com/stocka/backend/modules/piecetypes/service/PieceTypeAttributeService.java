package com.stocka.backend.modules.piecetypes.service;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.stocka.backend.modules.piecetypes.dto.UpdatePieceTypeAttributeDto;
import com.stocka.backend.modules.piecetypes.entity.PieceType;
import com.stocka.backend.modules.piecetypes.entity.PieceTypeAttribute;
import com.stocka.backend.modules.piecetypes.repository.PieceTypeAttributeRepository;

/**
 * Mutations on individual attributes of a piece type. Covers updates (display name, required,
 * position, validators) and soft-deletion. Each operation that affects whether a piece is
 * considered complete triggers a status recalc through {@link PieceTypeUsage}.
 */
@Service
public class PieceTypeAttributeService {
    private final PieceTypeService pieceTypeService;
    private final PieceTypeAttributeRepository attributeRepository;
    private final ValidatorsJsonCodec validatorsCodec;
    private final Optional<PieceTypeUsage> usage;

    public PieceTypeAttributeService(
            PieceTypeService pieceTypeService,
            PieceTypeAttributeRepository attributeRepository,
            ValidatorsJsonCodec validatorsCodec,
            Optional<PieceTypeUsage> usage
    ) {
        this.pieceTypeService = pieceTypeService;
        this.attributeRepository = attributeRepository;
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
            PieceTypeService.validateValidators(attr.getType(), dto.getValidators());
            attr.setValidatorsJson(validatorsCodec.serialize(dto.getValidators()));
            affectsStatus = true;
        }

        PieceTypeAttribute saved = attributeRepository.save(attr);
        if (affectsStatus) {
            usage.ifPresent(u -> u.recalcStatusForType(saved.getPieceType()));
        }
        return saved;
    }

    @Transactional
    public void softDelete(Integer orgId, Integer typeId, Integer attributeId) {
        PieceTypeAttribute attr = findInOrg(orgId, typeId, attributeId);
        usage.ifPresent(u -> u.removeValuesForAttribute(attr));
        attr.setDeletedAt(LocalDateTime.now());
        attributeRepository.save(attr);
        usage.ifPresent(u -> u.recalcStatusForType(attr.getPieceType()));
    }
}
