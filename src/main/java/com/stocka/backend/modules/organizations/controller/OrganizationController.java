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
import com.stocka.backend.modules.organizations.dto.OrganizationLookupResponseDto;
import com.stocka.backend.modules.organizations.dto.OrganizationResponseDto;
import com.stocka.backend.modules.organizations.dto.UpdateOrganizationDto;
import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.organizations.entity.OrganizationRoleEnum;
import com.stocka.backend.modules.organizations.security.OrganizationSecurity;
import com.stocka.backend.modules.organizations.service.OrganizationResolver;
import com.stocka.backend.modules.organizations.service.OrganizationResolver.Resolved;
import com.stocka.backend.modules.organizations.service.OrganizationService;
import com.stocka.backend.modules.users.entity.User;

@RestController
@RequestMapping("/organizations")
public class OrganizationController {
    private final OrganizationService organizationService;
    private final OrganizationResolver orgResolver;
    private final OrganizationSecurity orgSecurity;

    public OrganizationController(
            OrganizationService organizationService,
            OrganizationResolver orgResolver,
            OrganizationSecurity orgSecurity
    ) {
        this.organizationService = organizationService;
        this.orgResolver = orgResolver;
        this.orgSecurity = orgSecurity;
    }

    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<OrganizationResponseDto> create(@RequestBody CreateOrganizationDto dto) {
        User actor = currentUser();
        Organization org = organizationService.create(dto, actor);
        return ResponseEntity.ok(OrganizationResponseDto.from(org, OrganizationRoleEnum.OWNER,
                orgSecurity.pieceTypeActionsEnabled(org, actor), orgSecurity.portsEnabled(org, actor)));
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
                        organizationService.getCurrentUserRole(o, actor).orElse(null),
                        orgSecurity.pieceTypeActionsEnabled(o, actor), orgSecurity.portsEnabled(o, actor)))
                .toList();
        return ResponseEntity.ok(orgs);
    }

    /**
     * Resolves an organization by slug for the frontend router. Accepts both current and
     * historical slugs so deep links generated before a slug rename can be redirected.
     *
     * @param slug current or historical slug
     * @return the organization with the caller's role plus the up-to-date slug
     */
    @GetMapping("/by-slug/{slug}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<OrganizationLookupResponseDto> lookupBySlug(@PathVariable String slug) {
        User actor = currentUser();
        Resolved resolved = orgResolver.resolve(slug);
        Organization org = resolved.organization();
        OrganizationRoleEnum role = organizationService.getCurrentUserRole(org, actor).orElse(null);
        if (role == null && !com.stocka.backend.modules.organizations.security.OrganizationSecurity.isGlobalAdmin(actor)) {
            // Hide existence: a non-member should not be able to probe slugs.
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(new OrganizationLookupResponseDto(
                OrganizationResponseDto.from(org, role, orgSecurity.pieceTypeActionsEnabled(org, actor),
                        orgSecurity.portsEnabled(org, actor)),
                resolved.historical(),
                resolved.currentSlug()
        ));
    }

    @GetMapping("/{orgSlug}")
    @PreAuthorize("@orgSecurity.isMember(#orgSlug, principal)")
    public ResponseEntity<OrganizationResponseDto> getOne(@PathVariable String orgSlug) {
        User actor = currentUser();
        Organization org = orgResolver.requireCurrent(orgSlug);
        return ResponseEntity.ok(OrganizationResponseDto.from(org,
                organizationService.getCurrentUserRole(org, actor).orElse(null),
                orgSecurity.pieceTypeActionsEnabled(org, actor), orgSecurity.portsEnabled(org, actor)));
    }

    @PatchMapping("/{orgSlug}")
    @PreAuthorize("@orgSecurity.isOwner(#orgSlug, principal)")
    public ResponseEntity<OrganizationResponseDto> update(
            @PathVariable String orgSlug,
            @RequestBody UpdateOrganizationDto dto
    ) {
        User actor = currentUser();
        Organization current = orgResolver.requireCurrent(orgSlug);
        Organization org = organizationService.update(current.getId(), dto, actor);
        return ResponseEntity.ok(OrganizationResponseDto.from(org,
                organizationService.getCurrentUserRole(org, actor).orElse(null),
                orgSecurity.pieceTypeActionsEnabled(org, actor), orgSecurity.portsEnabled(org, actor)));
    }

    @DeleteMapping("/{orgSlug}")
    @PreAuthorize("@orgSecurity.isOwner(#orgSlug, principal)")
    public ResponseEntity<Void> delete(@PathVariable String orgSlug) {
        User actor = currentUser();
        Organization current = orgResolver.requireCurrent(orgSlug);
        organizationService.softDelete(current.getId(), actor);
        return ResponseEntity.noContent().build();
    }

    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (User) auth.getPrincipal();
    }
}
