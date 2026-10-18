package com.stocka.backend.modules.search.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.stocka.backend.modules.organizations.service.OrganizationResolver;
import com.stocka.backend.modules.search.dto.SearchStatusDto;
import com.stocka.backend.modules.search.service.SemanticSearchService;

/**
 * Semantic search of an organization: index status for every member, rebuild for owners and
 * managers. The search itself happens through {@code GET /organizations/{orgSlug}/pieces?q=}.
 */
@RestController
@RequestMapping("/organizations/{orgSlug}/search")
public class SemanticSearchController {

    private final SemanticSearchService service;
    private final OrganizationResolver orgResolver;

    public SemanticSearchController(SemanticSearchService service, OrganizationResolver orgResolver) {
        this.service = service;
        this.orgResolver = orgResolver;
    }

    @GetMapping("/status")
    @PreAuthorize("@orgSecurity.canReadOrgContent(#orgSlug, principal)")
    public ResponseEntity<SearchStatusDto> status(@PathVariable String orgSlug) {
        return ResponseEntity.ok(service.status(orgResolver.requireCurrent(orgSlug)));
    }

    @PostMapping("/reindex")
    @PreAuthorize("@orgSecurity.canManageOrgContent(#orgSlug, principal)")
    public ResponseEntity<SearchStatusDto> reindex(@PathVariable String orgSlug) {
        return ResponseEntity.ok(service.reindex(orgResolver.requireCurrent(orgSlug)));
    }
}
