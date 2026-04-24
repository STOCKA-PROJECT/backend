package com.stocka.backend.modules.organizations.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.stocka.backend.modules.organizations.dto.MemberResponseDto;
import com.stocka.backend.modules.organizations.dto.UpdateMemberRoleDto;
import com.stocka.backend.modules.organizations.entity.OrganizationMember;
import com.stocka.backend.modules.organizations.service.OrganizationMemberService;
import com.stocka.backend.modules.users.entity.User;

@RestController
@RequestMapping("/organizations/{orgId}/members")
public class OrganizationMemberController {
    private final OrganizationMemberService memberService;

    public OrganizationMemberController(OrganizationMemberService memberService) {
        this.memberService = memberService;
    }

    @GetMapping
    @PreAuthorize("@orgSecurity.isMember(#orgId, principal)")
    public ResponseEntity<List<MemberResponseDto>> list(@PathVariable Integer orgId) {
        List<MemberResponseDto> members = memberService.listMembers(orgId).stream()
                .map(MemberResponseDto::from)
                .toList();
        return ResponseEntity.ok(members);
    }

    @PatchMapping("/{memberId}")
    @PreAuthorize("@orgSecurity.isOwner(#orgId, principal)")
    public ResponseEntity<MemberResponseDto> updateRole(
            @PathVariable Integer orgId,
            @PathVariable Integer memberId,
            @RequestBody UpdateMemberRoleDto dto
    ) {
        OrganizationMember member = memberService.updateMemberRole(orgId, memberId, dto.getRole(), currentUser());
        return ResponseEntity.ok(MemberResponseDto.from(member));
    }

    @DeleteMapping("/{memberId}")
    @PreAuthorize("@orgSecurity.isOwnerOrManager(#orgId, principal)")
    public ResponseEntity<Void> remove(
            @PathVariable Integer orgId,
            @PathVariable Integer memberId
    ) {
        memberService.removeMember(orgId, memberId, currentUser());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/me")
    @PreAuthorize("@orgSecurity.isMember(#orgId, principal)")
    public ResponseEntity<Void> leave(@PathVariable Integer orgId) {
        memberService.leaveOrganization(orgId, currentUser());
        return ResponseEntity.noContent().build();
    }

    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (User) auth.getPrincipal();
    }
}
