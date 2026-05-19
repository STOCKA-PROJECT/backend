package com.stocka.backend.modules.piecetypes.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.stocka.backend.modules.organizations.service.OrganizationResolver;
import com.stocka.backend.modules.piecetypes.dto.CreatePieceTypeAttributeDto;
import com.stocka.backend.modules.piecetypes.dto.PieceTypeAttributeResponseDto;
import com.stocka.backend.modules.piecetypes.dto.UpdatePieceTypeAttributeDto;
import com.stocka.backend.modules.piecetypes.entity.PieceTypeAttribute;
import com.stocka.backend.modules.piecetypes.service.PieceTypeAttributeService;
import com.stocka.backend.modules.piecetypes.service.PieceTypeService;
import com.stocka.backend.modules.piecetypes.service.ValidatorsJsonCodec;

@RestController
@RequestMapping("/organizations/{orgSlug}/piece-types/{typeId}/attributes")
public class PieceTypeAttributeController {
    private final PieceTypeService pieceTypeService;
    private final PieceTypeAttributeService attributeService;
    private final ValidatorsJsonCodec validatorsCodec;
    private final OrganizationResolver orgResolver;

    public PieceTypeAttributeController(
            PieceTypeService pieceTypeService,
            PieceTypeAttributeService attributeService,
            ValidatorsJsonCodec validatorsCodec,
            OrganizationResolver orgResolver
    ) {
        this.pieceTypeService = pieceTypeService;
        this.attributeService = attributeService;
        this.validatorsCodec = validatorsCodec;
        this.orgResolver = orgResolver;
    }

    @PostMapping
    @PreAuthorize("@orgSecurity.canManageOrgContent(#orgSlug, principal)")
    public ResponseEntity<PieceTypeAttributeResponseDto> add(
            @PathVariable String orgSlug,
            @PathVariable Integer typeId,
            @RequestBody CreatePieceTypeAttributeDto dto
    ) {
        Integer orgId = orgResolver.requireCurrent(orgSlug).getId();
        PieceTypeAttribute attr = pieceTypeService.addAttribute(orgId, typeId, dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(attr));
    }

    @PatchMapping("/{attributeId}")
    @PreAuthorize("@orgSecurity.canManageOrgContent(#orgSlug, principal)")
    public ResponseEntity<PieceTypeAttributeResponseDto> update(
            @PathVariable String orgSlug,
            @PathVariable Integer typeId,
            @PathVariable Integer attributeId,
            @RequestBody UpdatePieceTypeAttributeDto dto
    ) {
        Integer orgId = orgResolver.requireCurrent(orgSlug).getId();
        PieceTypeAttribute attr = attributeService.update(orgId, typeId, attributeId, dto);
        return ResponseEntity.ok(toResponse(attr));
    }

    @DeleteMapping("/{attributeId}")
    @PreAuthorize("@orgSecurity.canManageOrgContent(#orgSlug, principal)")
    public ResponseEntity<Void> delete(
            @PathVariable String orgSlug,
            @PathVariable Integer typeId,
            @PathVariable Integer attributeId
    ) {
        Integer orgId = orgResolver.requireCurrent(orgSlug).getId();
        attributeService.softDelete(orgId, typeId, attributeId);
        return ResponseEntity.noContent().build();
    }

    private PieceTypeAttributeResponseDto toResponse(PieceTypeAttribute attr) {
        return PieceTypeAttributeResponseDto.from(attr, validatorsCodec.deserialize(attr.getValidatorsJson()));
    }
}
