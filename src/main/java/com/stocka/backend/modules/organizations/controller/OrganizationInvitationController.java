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
import com.stocka.backend.modules.organizations.service.OrganizationResolver;
import com.stocka.backend.modules.users.entity.User;

@RestController
@RequestMapping("/organizations/{orgSlug}/invitations")
public class OrganizationInvitationController {
    private final OrganizationInvitationService invitationService;
    private final OrganizationResolver orgResolver;

    public OrganizationInvitationController(
            OrganizationInvitationService invitationService,
            OrganizationResolver orgResolver
    ) {
        this.invitationService = invitationService;
        this.orgResolver = orgResolver;
    }

    @PostMapping
    @PreAuthorize("@orgSecurity.isOwnerOrManager(#orgSlug, principal)")
    public ResponseEntity<InvitationResponseDto> create(
            @PathVariable String orgSlug,
            @RequestBody CreateInvitationDto dto
    ) {
        Integer orgId = orgResolver.requireCurrent(orgSlug).getId();
        OrganizationInvitation invitation = invitationService.createInvitation(orgId, dto, currentUser());
        return ResponseEntity.ok(InvitationResponseDto.from(invitation, true));
    }

    @GetMapping
    @PreAuthorize("@orgSecurity.isOwnerOrManager(#orgSlug, principal)")
    public ResponseEntity<List<InvitationResponseDto>> listPending(@PathVariable String orgSlug) {
        Integer orgId = orgResolver.requireCurrent(orgSlug).getId();
        List<InvitationResponseDto> pending = invitationService.listPendingInvitations(orgId).stream()
                .map(i -> InvitationResponseDto.from(i, true))
                .toList();
        return ResponseEntity.ok(pending);
    }

    @DeleteMapping("/{invitationId}")
    @PreAuthorize("@orgSecurity.isOwnerOrManager(#orgSlug, principal)")
    public ResponseEntity<Void> cancel(
            @PathVariable String orgSlug,
            @PathVariable Integer invitationId
    ) {
        Integer orgId = orgResolver.requireCurrent(orgSlug).getId();
        invitationService.cancelInvitation(orgId, invitationId, currentUser());
        return ResponseEntity.noContent().build();
    }

    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (User) auth.getPrincipal();
    }
}
