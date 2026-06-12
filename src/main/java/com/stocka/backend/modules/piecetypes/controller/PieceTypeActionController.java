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
import com.stocka.backend.modules.piecetypes.dto.CreatePieceTypeActionDto;
import com.stocka.backend.modules.piecetypes.dto.PieceTypeActionResponseDto;
import com.stocka.backend.modules.piecetypes.dto.UpdatePieceTypeActionDto;
import com.stocka.backend.modules.piecetypes.entity.PieceType;
import com.stocka.backend.modules.piecetypes.entity.PieceTypeAction;
import com.stocka.backend.modules.piecetypes.service.PieceTypeActionService;
import com.stocka.backend.modules.piecetypes.service.PieceTypeService;

/**
 * Sub-resource exposing the actions (functions with typed parameters) of a piece type.
 *
 * <p>This is a private, organization-gated feature: only organizations whose owner is a global
 * admin expose actions. Reading is allowed to any member of such an organization (and to global
 * admins); creating/editing/deleting requires OWNER or MANAGER there. The gating lives in
 * {@code @orgSecurity.canReadPieceTypeActions} / {@code canManagePieceTypeActions}. Actions are a
 * separate sub-resource (not embedded in {@code PieceTypeResponseDto}) so the feature is never
 * surfaced to organizations that are not allowed to use it.
 */
@RestController
@RequestMapping("/organizations/{orgSlug}/piece-types/{typeId}/actions")
public class PieceTypeActionController {
    private final PieceTypeService pieceTypeService;
    private final PieceTypeActionService actionService;
    private final OrganizationResolver orgResolver;

    public PieceTypeActionController(
            PieceTypeService pieceTypeService,
            PieceTypeActionService actionService,
            OrganizationResolver orgResolver
    ) {
        this.pieceTypeService = pieceTypeService;
        this.actionService = actionService;
        this.orgResolver = orgResolver;
    }

    @GetMapping
    @PreAuthorize("@orgSecurity.canReadPieceTypeActions(#orgSlug, principal)")
    public ResponseEntity<List<PieceTypeActionResponseDto>> list(
            @PathVariable String orgSlug,
            @PathVariable Integer typeId
    ) {
        Integer orgId = orgResolver.requireCurrent(orgSlug).getId();
        PieceType type = pieceTypeService.findInOrg(orgId, typeId);
        List<PieceTypeActionResponseDto> out = actionService.listOf(type).stream()
                .map(this::toResponse)
                .toList();
        return ResponseEntity.ok(out);
    }

    @PostMapping
    @PreAuthorize("@orgSecurity.canManagePieceTypeActions(#orgSlug, principal)")
    public ResponseEntity<PieceTypeActionResponseDto> add(
            @PathVariable String orgSlug,
            @PathVariable Integer typeId,
            @RequestBody CreatePieceTypeActionDto dto
    ) {
        Integer orgId = orgResolver.requireCurrent(orgSlug).getId();
        PieceTypeAction action = actionService.create(orgId, typeId, dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(action));
    }

    @PatchMapping("/{actionId}")
    @PreAuthorize("@orgSecurity.canManagePieceTypeActions(#orgSlug, principal)")
    public ResponseEntity<PieceTypeActionResponseDto> update(
            @PathVariable String orgSlug,
            @PathVariable Integer typeId,
            @PathVariable Integer actionId,
            @RequestBody UpdatePieceTypeActionDto dto
    ) {
        Integer orgId = orgResolver.requireCurrent(orgSlug).getId();
        PieceTypeAction action = actionService.update(orgId, typeId, actionId, dto);
        return ResponseEntity.ok(toResponse(action));
    }

    @DeleteMapping("/{actionId}")
    @PreAuthorize("@orgSecurity.canManagePieceTypeActions(#orgSlug, principal)")
    public ResponseEntity<Void> delete(
            @PathVariable String orgSlug,
            @PathVariable Integer typeId,
            @PathVariable Integer actionId
    ) {
        Integer orgId = orgResolver.requireCurrent(orgSlug).getId();
        actionService.softDelete(orgId, typeId, actionId);
        return ResponseEntity.noContent().build();
    }

    private PieceTypeActionResponseDto toResponse(PieceTypeAction action) {
        return PieceTypeActionResponseDto.from(action, actionService.parametersOf(action));
    }
}
