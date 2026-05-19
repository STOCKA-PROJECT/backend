package com.stocka.backend.modules.pieces.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.stocka.backend.modules.organizations.dto.OrganizationUsageDto;
import com.stocka.backend.modules.organizations.service.OrganizationResolver;
import com.stocka.backend.modules.pieces.service.OrganizationUsageService;

/**
 * Exposes per-organization quota usage so OWNERs/MANAGERs can monitor consumption against
 * the configured limits (issue #21).
 */
@RestController
@RequestMapping("/organizations/{orgSlug}/usage")
public class OrganizationUsageController {
    private final OrganizationUsageService usageService;
    private final OrganizationResolver orgResolver;

    public OrganizationUsageController(OrganizationUsageService usageService, OrganizationResolver orgResolver) {
        this.usageService = usageService;
        this.orgResolver = orgResolver;
    }

    @GetMapping
    @PreAuthorize("@orgSecurity.canManageOrgContent(#orgSlug, principal)")
    public ResponseEntity<OrganizationUsageDto> getUsage(@PathVariable String orgSlug) {
        Integer orgId = orgResolver.requireCurrent(orgSlug).getId();
        return ResponseEntity.ok(usageService.getUsage(orgId));
    }
}
