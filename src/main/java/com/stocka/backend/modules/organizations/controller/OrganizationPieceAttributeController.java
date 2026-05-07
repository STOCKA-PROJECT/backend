package com.stocka.backend.modules.organizations.controller;

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

import com.stocka.backend.modules.organizations.dto.CreateOrganizationPieceAttributeDto;
import com.stocka.backend.modules.organizations.dto.OrganizationPieceAttributeResponseDto;
import com.stocka.backend.modules.organizations.dto.UpdateOrganizationPieceAttributeDto;
import com.stocka.backend.modules.organizations.entity.OrganizationPieceAttribute;
import com.stocka.backend.modules.organizations.service.OrganizationPieceAttributeService;
import com.stocka.backend.modules.piecetypes.service.ValidatorsJsonCodec;

/**
 * REST endpoints for the organization-level piece attributes. Read access is open to any
 * organization member; write access requires OWNER or MANAGER role (same policy as for
 * piece-types and their attributes).
 */
@RestController
@RequestMapping("/organizations/{orgId}/piece-attributes")
public class OrganizationPieceAttributeController {
    private final OrganizationPieceAttributeService attributeService;
    private final ValidatorsJsonCodec validatorsCodec;

    public OrganizationPieceAttributeController(
            OrganizationPieceAttributeService attributeService,
            ValidatorsJsonCodec validatorsCodec
    ) {
        this.attributeService = attributeService;
        this.validatorsCodec = validatorsCodec;
    }

    @GetMapping
    @PreAuthorize("@orgSecurity.canReadOrgContent(#orgId, principal)")
    public ResponseEntity<List<OrganizationPieceAttributeResponseDto>> list(@PathVariable Integer orgId) {
        List<OrganizationPieceAttributeResponseDto> body = attributeService.listAll(orgId).stream()
                .map(this::toResponse)
                .toList();
        return ResponseEntity.ok(body);
    }

    @PostMapping
    @PreAuthorize("@orgSecurity.canManageOrgContent(#orgId, principal)")
    public ResponseEntity<OrganizationPieceAttributeResponseDto> create(
            @PathVariable Integer orgId,
            @RequestBody CreateOrganizationPieceAttributeDto dto
    ) {
        OrganizationPieceAttribute attr = attributeService.create(orgId, dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(attr));
    }

    @PatchMapping("/{attributeId}")
    @PreAuthorize("@orgSecurity.canManageOrgContent(#orgId, principal)")
    public ResponseEntity<OrganizationPieceAttributeResponseDto> update(
            @PathVariable Integer orgId,
            @PathVariable Integer attributeId,
            @RequestBody UpdateOrganizationPieceAttributeDto dto
    ) {
        OrganizationPieceAttribute attr = attributeService.update(orgId, attributeId, dto);
        return ResponseEntity.ok(toResponse(attr));
    }

    @DeleteMapping("/{attributeId}")
    @PreAuthorize("@orgSecurity.canManageOrgContent(#orgId, principal)")
    public ResponseEntity<Void> delete(
            @PathVariable Integer orgId,
            @PathVariable Integer attributeId
    ) {
        attributeService.softDelete(orgId, attributeId);
        return ResponseEntity.noContent().build();
    }

    private OrganizationPieceAttributeResponseDto toResponse(OrganizationPieceAttribute attr) {
        return OrganizationPieceAttributeResponseDto.from(
                attr, validatorsCodec.deserialize(attr.getValidatorsJson()));
    }
}
