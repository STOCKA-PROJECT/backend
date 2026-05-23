package com.stocka.backend.modules.piecetypes.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.stocka.backend.modules.organizations.service.OrganizationResolver;
import com.stocka.backend.modules.piecetypes.dto.CreatePieceTypeDto;
import com.stocka.backend.modules.piecetypes.dto.PieceTypeAttributeResponseDto;
import com.stocka.backend.modules.piecetypes.dto.PieceTypeResponseDto;
import com.stocka.backend.modules.piecetypes.dto.UpdatePieceTypeDto;
import com.stocka.backend.modules.piecetypes.entity.PieceType;
import com.stocka.backend.modules.piecetypes.entity.PieceTypeAttribute;
import com.stocka.backend.modules.piecetypes.service.PieceTypeService;
import com.stocka.backend.modules.piecetypes.service.ValidatorsJsonCodec;

@RestController
@RequestMapping("/organizations/{orgSlug}/piece-types")
public class PieceTypeController {
    private final PieceTypeService pieceTypeService;
    private final ValidatorsJsonCodec validatorsCodec;
    private final OrganizationResolver orgResolver;

    public PieceTypeController(
            PieceTypeService pieceTypeService,
            ValidatorsJsonCodec validatorsCodec,
            OrganizationResolver orgResolver
    ) {
        this.pieceTypeService = pieceTypeService;
        this.validatorsCodec = validatorsCodec;
        this.orgResolver = orgResolver;
    }

    @PostMapping
    @PreAuthorize("@orgSecurity.canManageOrgContent(#orgSlug, principal)")
    public ResponseEntity<PieceTypeResponseDto> create(
            @PathVariable String orgSlug,
            @RequestBody CreatePieceTypeDto dto
    ) {
        Integer orgId = orgResolver.requireCurrent(orgSlug).getId();
        PieceType type = pieceTypeService.create(orgId, dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(type));
    }

    @GetMapping
    @PreAuthorize("@orgSecurity.canReadOrgContent(#orgSlug, principal)")
    public ResponseEntity<List<PieceTypeResponseDto>> list(@PathVariable String orgSlug) {
        Integer orgId = orgResolver.requireCurrent(orgSlug).getId();
        List<PieceTypeResponseDto> out = pieceTypeService.listAll(orgId).stream()
                .map(this::toResponse)
                .toList();
        return ResponseEntity.ok(out);
    }

    @GetMapping("/{typeId}")
    @PreAuthorize("@orgSecurity.canReadOrgContent(#orgSlug, principal)")
    public ResponseEntity<PieceTypeResponseDto> getOne(
            @PathVariable String orgSlug,
            @PathVariable Integer typeId
    ) {
        Integer orgId = orgResolver.requireCurrent(orgSlug).getId();
        return ResponseEntity.ok(toResponse(pieceTypeService.findInOrg(orgId, typeId)));
    }

    @PatchMapping("/{typeId}")
    @PreAuthorize("@orgSecurity.canManageOrgContent(#orgSlug, principal)")
    public ResponseEntity<PieceTypeResponseDto> update(
            @PathVariable String orgSlug,
            @PathVariable Integer typeId,
            @RequestBody UpdatePieceTypeDto dto
    ) {
        Integer orgId = orgResolver.requireCurrent(orgSlug).getId();
        return ResponseEntity.ok(toResponse(pieceTypeService.update(orgId, typeId, dto)));
    }

    @DeleteMapping("/{typeId}")
    @PreAuthorize("@orgSecurity.canManageOrgContent(#orgSlug, principal)")
    public ResponseEntity<Void> delete(
            @PathVariable String orgSlug,
            @PathVariable Integer typeId
    ) {
        Integer orgId = orgResolver.requireCurrent(orgSlug).getId();
        pieceTypeService.softDelete(orgId, typeId);
        return ResponseEntity.noContent().build();
    }

    private PieceTypeResponseDto toResponse(PieceType type) {
        List<PieceTypeAttribute> attrs = pieceTypeService.attributesOf(type);
        List<PieceTypeAttributeResponseDto> dtos = attrs.stream()
                .map(a -> PieceTypeAttributeResponseDto.from(a, validatorsCodec.deserialize(a.getValidatorsJson())))
                .toList();
        return PieceTypeResponseDto.from(type, dtos);
    }
}
