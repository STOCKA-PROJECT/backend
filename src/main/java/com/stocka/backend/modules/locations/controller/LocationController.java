package com.stocka.backend.modules.locations.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.stocka.backend.modules.locations.dto.CreateLocationDto;
import com.stocka.backend.modules.locations.dto.LocationResponseDto;
import com.stocka.backend.modules.locations.dto.LocationTreeNodeDto;
import com.stocka.backend.modules.locations.dto.UpdateLocationDto;
import com.stocka.backend.modules.locations.entity.Location;
import com.stocka.backend.modules.locations.service.LocationService;
import com.stocka.backend.modules.organizations.service.OrganizationResolver;

/**
 * REST endpoints for hierarchical locations under an organization.
 *
 * <p>Reads accept any organization member (including SPECTATOR); writes require OWNER or MANAGER.
 */
@RestController
@RequestMapping("/organizations/{orgSlug}/locations")
public class LocationController {
    private final LocationService locationService;
    private final OrganizationResolver orgResolver;

    public LocationController(LocationService locationService, OrganizationResolver orgResolver) {
        this.locationService = locationService;
        this.orgResolver = orgResolver;
    }

    @PostMapping
    @PreAuthorize("@orgSecurity.canManageOrgContent(#orgSlug, principal)")
    public ResponseEntity<LocationResponseDto> create(
            @PathVariable String orgSlug,
            @RequestBody CreateLocationDto dto
    ) {
        Integer orgId = orgResolver.requireCurrent(orgSlug).getId();
        Location loc = locationService.create(orgId, dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(LocationResponseDto.from(loc));
    }

    @GetMapping
    @PreAuthorize("@orgSecurity.canReadOrgContent(#orgSlug, principal)")
    public ResponseEntity<List<LocationResponseDto>> list(@PathVariable String orgSlug) {
        Integer orgId = orgResolver.requireCurrent(orgSlug).getId();
        List<LocationResponseDto> out = locationService.listAll(orgId).stream()
                .map(LocationResponseDto::from)
                .toList();
        return ResponseEntity.ok(out);
    }

    @GetMapping("/tree")
    @PreAuthorize("@orgSecurity.canReadOrgContent(#orgSlug, principal)")
    public ResponseEntity<List<LocationTreeNodeDto>> tree(@PathVariable String orgSlug) {
        Integer orgId = orgResolver.requireCurrent(orgSlug).getId();
        return ResponseEntity.ok(locationService.tree(orgId));
    }

    @GetMapping("/{locationId}")
    @PreAuthorize("@orgSecurity.canReadOrgContent(#orgSlug, principal)")
    public ResponseEntity<LocationResponseDto> getOne(
            @PathVariable String orgSlug,
            @PathVariable Integer locationId
    ) {
        Integer orgId = orgResolver.requireCurrent(orgSlug).getId();
        Location loc = locationService.findInOrg(orgId, locationId);
        return ResponseEntity.ok(LocationResponseDto.from(loc, locationService.breadcrumb(loc)));
    }

    @PatchMapping("/{locationId}")
    @PreAuthorize("@orgSecurity.canManageOrgContent(#orgSlug, principal)")
    public ResponseEntity<LocationResponseDto> update(
            @PathVariable String orgSlug,
            @PathVariable Integer locationId,
            @RequestBody UpdateLocationDto dto
    ) {
        Integer orgId = orgResolver.requireCurrent(orgSlug).getId();
        Location loc = locationService.update(orgId, locationId, dto);
        return ResponseEntity.ok(LocationResponseDto.from(loc, locationService.breadcrumb(loc)));
    }

    @DeleteMapping("/{locationId}")
    @PreAuthorize("@orgSecurity.canManageOrgContent(#orgSlug, principal)")
    public ResponseEntity<Void> delete(
            @PathVariable String orgSlug,
            @PathVariable Integer locationId
    ) {
        Integer orgId = orgResolver.requireCurrent(orgSlug).getId();
        locationService.softDelete(orgId, locationId);
        return ResponseEntity.noContent().build();
    }
}
