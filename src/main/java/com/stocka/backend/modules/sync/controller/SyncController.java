package com.stocka.backend.modules.sync.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.stocka.backend.modules.organizations.service.OrganizationResolver;
import com.stocka.backend.modules.sync.dto.SyncChangesResponse;
import com.stocka.backend.modules.sync.service.SyncService;

/**
 * Offline synchronization endpoints (v1) for the desktop client.
 *
 * <p>The pull feed returns documents changed since the client's per-collection checkpoint,
 * tombstones included, so the local RxDB store can converge. Reads are allowed for any
 * organization member (including SPECTATOR).
 *
 * @since 0.2.0
 */
@RestController
@RequestMapping("/organizations/{orgSlug}/sync/v1")
public class SyncController {

    private final SyncService syncService;
    private final OrganizationResolver orgResolver;

    public SyncController(SyncService syncService, OrganizationResolver orgResolver) {
        this.syncService = syncService;
        this.orgResolver = orgResolver;
    }

    /**
     * Returns the next page of changes for the organization.
     *
     * @param orgSlug organization slug
     * @param since   per-collection checkpoint as {@code "collection:rev,collection:rev"} (optional)
     * @param limit   requested page size (optional; clamped server-side)
     * @return the changed documents, advanced checkpoint and {@code hasMore} flag
     */
    @GetMapping("/changes")
    @PreAuthorize("@orgSecurity.canReadOrgContent(#orgSlug, principal)")
    public ResponseEntity<SyncChangesResponse> changes(
            @PathVariable String orgSlug,
            @RequestParam(name = "since", required = false) String since,
            @RequestParam(name = "limit", defaultValue = "0") int limit) {
        Integer orgId = orgResolver.requireCurrent(orgSlug).getId();
        SyncChangesResponse response = syncService.pull(orgId, SyncService.parseCheckpoint(since), limit);
        return ResponseEntity.ok(response);
    }
}
