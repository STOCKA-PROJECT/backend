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
import com.stocka.backend.modules.organizations.service.OrganizationResolver;
import com.stocka.backend.modules.users.entity.User;

@RestController
@RequestMapping("/organizations/{orgSlug}/members")
public class OrganizationMemberController {
    private final OrganizationMemberService memberService;
    private final OrganizationResolver orgResolver;

    public OrganizationMemberController(OrganizationMemberService memberService, OrganizationResolver orgResolver) {
        this.memberService = memberService;
        this.orgResolver = orgResolver;
    }

    @GetMapping
    @PreAuthorize("@orgSecurity.isMember(#orgSlug, principal)")
    public ResponseEntity<List<MemberResponseDto>> list(@PathVariable String orgSlug) {
        Integer orgId = orgResolver.requireCurrent(orgSlug).getId();
        List<MemberResponseDto> members = memberService.listMembers(orgId).stream()
                .map(MemberResponseDto::from)
                .toList();
        return ResponseEntity.ok(members);
    }

    @PatchMapping("/{memberId}")
    @PreAuthorize("@orgSecurity.isOwner(#orgSlug, principal)")
    public ResponseEntity<MemberResponseDto> updateRole(
            @PathVariable String orgSlug,
            @PathVariable Integer memberId,
            @RequestBody UpdateMemberRoleDto dto
    ) {
        Integer orgId = orgResolver.requireCurrent(orgSlug).getId();
        OrganizationMember member = memberService.updateMemberRole(orgId, memberId, dto.getRole(), currentUser());
        return ResponseEntity.ok(MemberResponseDto.from(member));
    }

    @DeleteMapping("/{memberId}")
    @PreAuthorize("@orgSecurity.isOwnerOrManager(#orgSlug, principal)")
    public ResponseEntity<Void> remove(
            @PathVariable String orgSlug,
            @PathVariable Integer memberId
    ) {
        Integer orgId = orgResolver.requireCurrent(orgSlug).getId();
        memberService.removeMember(orgId, memberId, currentUser());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/me")
    @PreAuthorize("@orgSecurity.isMember(#orgSlug, principal)")
    public ResponseEntity<Void> leave(@PathVariable String orgSlug) {
        Integer orgId = orgResolver.requireCurrent(orgSlug).getId();
        memberService.leaveOrganization(orgId, currentUser());
        return ResponseEntity.noContent().build();
    }

    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (User) auth.getPrincipal();
    }
}
