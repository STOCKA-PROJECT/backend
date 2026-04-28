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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.stocka.backend.modules.common.dto.AvailabilityResponse;
import com.stocka.backend.modules.organizations.dto.CreateOrganizationDto;
import com.stocka.backend.modules.organizations.dto.OrganizationResponseDto;
import com.stocka.backend.modules.organizations.dto.UpdateOrganizationDto;
import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.organizations.entity.OrganizationRoleEnum;
import com.stocka.backend.modules.organizations.service.OrganizationService;
import com.stocka.backend.modules.users.entity.User;

@RestController
@RequestMapping("/organizations")
public class OrganizationController {
    private final OrganizationService organizationService;

    public OrganizationController(OrganizationService organizationService) {
        this.organizationService = organizationService;
    }

    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<OrganizationResponseDto> create(@RequestBody CreateOrganizationDto dto) {
        User actor = currentUser();
        Organization org = organizationService.create(dto, actor);
        return ResponseEntity.ok(OrganizationResponseDto.from(org, OrganizationRoleEnum.OWNER));
    }

    /**
     * Returns whether the given slug is available for a new organization.
     *
     * @param slug candidate slug
     * @return availability result with reason when unavailable
     */
    @GetMapping("/check-slug")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<AvailabilityResponse> checkSlug(@RequestParam String slug) {
        return ResponseEntity.ok(organizationService.checkSlugAvailability(slug));
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<OrganizationResponseDto>> listMine() {
        User actor = currentUser();
        List<OrganizationResponseDto> orgs = organizationService.findUserOrganizations(actor).stream()
                .map(o -> OrganizationResponseDto.from(o,
                        organizationService.getCurrentUserRole(o, actor).orElse(null)))
                .toList();
        return ResponseEntity.ok(orgs);
    }

    @GetMapping("/{orgId}")
    @PreAuthorize("@orgSecurity.isMember(#orgId, principal)")
    public ResponseEntity<OrganizationResponseDto> getOne(@PathVariable Integer orgId) {
        User actor = currentUser();
        Organization org = organizationService.findById(orgId);
        return ResponseEntity.ok(OrganizationResponseDto.from(org,
                organizationService.getCurrentUserRole(org, actor).orElse(null)));
    }

    @PatchMapping("/{orgId}")
    @PreAuthorize("@orgSecurity.isOwner(#orgId, principal)")
    public ResponseEntity<OrganizationResponseDto> update(
            @PathVariable Integer orgId,
            @RequestBody UpdateOrganizationDto dto
    ) {
        User actor = currentUser();
        Organization org = organizationService.update(orgId, dto, actor);
        return ResponseEntity.ok(OrganizationResponseDto.from(org,
                organizationService.getCurrentUserRole(org, actor).orElse(null)));
    }

    @DeleteMapping("/{orgId}")
    @PreAuthorize("@orgSecurity.isOwner(#orgId, principal)")
    public ResponseEntity<Void> delete(@PathVariable Integer orgId) {
        User actor = currentUser();
        organizationService.softDelete(orgId, actor);
        return ResponseEntity.noContent().build();
    }

    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (User) auth.getPrincipal();
    }
}
