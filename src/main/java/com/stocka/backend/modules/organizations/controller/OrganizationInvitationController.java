package com.stocka.backend.modules.organizations.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.stocka.backend.modules.organizations.dto.CreateInvitationDto;
import com.stocka.backend.modules.organizations.dto.InvitationResponseDto;
import com.stocka.backend.modules.organizations.entity.OrganizationInvitation;
import com.stocka.backend.modules.organizations.service.OrganizationInvitationService;
import com.stocka.backend.modules.users.entity.User;

@RestController
@RequestMapping("/organizations/{orgId}/invitations")
public class OrganizationInvitationController {
    private final OrganizationInvitationService invitationService;

    public OrganizationInvitationController(OrganizationInvitationService invitationService) {
        this.invitationService = invitationService;
    }

    @PostMapping
    @PreAuthorize("@orgSecurity.isOwnerOrManager(#orgId, principal)")
    public ResponseEntity<InvitationResponseDto> create(
            @PathVariable Integer orgId,
            @RequestBody CreateInvitationDto dto
    ) {
        OrganizationInvitation invitation = invitationService.createInvitation(orgId, dto, currentUser());
        return ResponseEntity.ok(InvitationResponseDto.from(invitation, true));
    }

    @GetMapping
    @PreAuthorize("@orgSecurity.isOwnerOrManager(#orgId, principal)")
    public ResponseEntity<List<InvitationResponseDto>> listPending(@PathVariable Integer orgId) {
        List<InvitationResponseDto> pending = invitationService.listPendingInvitations(orgId).stream()
                .map(i -> InvitationResponseDto.from(i, true))
                .toList();
        return ResponseEntity.ok(pending);
    }

    @DeleteMapping("/{invitationId}")
    @PreAuthorize("@orgSecurity.isOwnerOrManager(#orgId, principal)")
    public ResponseEntity<Void> cancel(
            @PathVariable Integer orgId,
            @PathVariable Integer invitationId
    ) {
        invitationService.cancelInvitation(invitationId, currentUser());
        return ResponseEntity.noContent().build();
    }

    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (User) auth.getPrincipal();
    }
}
