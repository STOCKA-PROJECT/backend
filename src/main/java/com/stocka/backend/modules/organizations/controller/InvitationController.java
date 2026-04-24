package com.stocka.backend.modules.organizations.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.stocka.backend.modules.organizations.dto.InvitationResponseDto;
import com.stocka.backend.modules.organizations.entity.OrganizationInvitation;
import com.stocka.backend.modules.organizations.service.OrganizationInvitationService;
import com.stocka.backend.modules.users.entity.User;

@RestController
@RequestMapping("/invitations")
public class InvitationController {
    private final OrganizationInvitationService invitationService;

    public InvitationController(OrganizationInvitationService invitationService) {
        this.invitationService = invitationService;
    }

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<InvitationResponseDto>> myInvitations() {
        List<InvitationResponseDto> invitations = invitationService.listMyInvitations(currentUser()).stream()
                .map(i -> InvitationResponseDto.from(i, true))
                .toList();
        return ResponseEntity.ok(invitations);
    }

    @PostMapping("/{token}/accept")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<InvitationResponseDto> accept(@PathVariable String token) {
        OrganizationInvitation invitation = invitationService.acceptInvitation(token, currentUser());
        return ResponseEntity.ok(InvitationResponseDto.from(invitation, false));
    }

    @PostMapping("/{token}/reject")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<InvitationResponseDto> reject(@PathVariable String token) {
        OrganizationInvitation invitation = invitationService.rejectInvitation(token, currentUser());
        return ResponseEntity.ok(InvitationResponseDto.from(invitation, false));
    }

    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (User) auth.getPrincipal();
    }
}
