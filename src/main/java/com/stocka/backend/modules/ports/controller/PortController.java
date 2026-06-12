package com.stocka.backend.modules.ports.controller;

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
import com.stocka.backend.modules.ports.dto.CreatePortDto;
import com.stocka.backend.modules.ports.dto.PortResponseDto;
import com.stocka.backend.modules.ports.dto.UpdatePortDto;
import com.stocka.backend.modules.ports.entity.Port;
import com.stocka.backend.modules.ports.service.PortService;

/**
 * REST resource exposing an organization's ports (Raspberry Pi GPIO outputs with typed parameters).
 *
 * <p>This is a private, organization-gated feature: only organizations whose owner is a global admin
 * expose ports. Reading is allowed to any member of such an organization (and to global admins);
 * creating/editing/deleting requires OWNER or MANAGER there. The gating lives in
 * {@code @orgSecurity.canReadPorts} / {@code canManagePorts}, mirroring the piece-type actions
 * feature.
 */
@RestController
@RequestMapping("/organizations/{orgSlug}/ports")
public class PortController {
    private final PortService portService;
    private final OrganizationResolver orgResolver;

    public PortController(PortService portService, OrganizationResolver orgResolver) {
        this.portService = portService;
        this.orgResolver = orgResolver;
    }

    @GetMapping
    @PreAuthorize("@orgSecurity.canReadPorts(#orgSlug, principal)")
    public ResponseEntity<List<PortResponseDto>> list(@PathVariable String orgSlug) {
        Integer orgId = orgResolver.requireCurrent(orgSlug).getId();
        List<PortResponseDto> out = portService.listAll(orgId).stream()
                .map(this::toResponse)
                .toList();
        return ResponseEntity.ok(out);
    }

    @PostMapping
    @PreAuthorize("@orgSecurity.canManagePorts(#orgSlug, principal)")
    public ResponseEntity<PortResponseDto> add(
            @PathVariable String orgSlug,
            @RequestBody CreatePortDto dto
    ) {
        Integer orgId = orgResolver.requireCurrent(orgSlug).getId();
        Port port = portService.create(orgId, dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(port));
    }

    @PatchMapping("/{portId}")
    @PreAuthorize("@orgSecurity.canManagePorts(#orgSlug, principal)")
    public ResponseEntity<PortResponseDto> update(
            @PathVariable String orgSlug,
            @PathVariable Integer portId,
            @RequestBody UpdatePortDto dto
    ) {
        Integer orgId = orgResolver.requireCurrent(orgSlug).getId();
        Port port = portService.update(orgId, portId, dto);
        return ResponseEntity.ok(toResponse(port));
    }

    @DeleteMapping("/{portId}")
    @PreAuthorize("@orgSecurity.canManagePorts(#orgSlug, principal)")
    public ResponseEntity<Void> delete(
            @PathVariable String orgSlug,
            @PathVariable Integer portId
    ) {
        Integer orgId = orgResolver.requireCurrent(orgSlug).getId();
        portService.softDelete(orgId, portId);
        return ResponseEntity.noContent().build();
    }

    private PortResponseDto toResponse(Port port) {
        return PortResponseDto.from(port, portService.parametersOf(port),
                portService.pieceTypeNameOf(port.getPieceTypeId()));
    }
}
