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

/**
 * REST endpoints for hierarchical locations under an organization.
 *
 * <p>Reads accept any organization member (including SPECTATOR); writes require OWNER or MANAGER.
 */
@RestController
@RequestMapping("/organizations/{orgId}/locations")
public class LocationController {
    private final LocationService locationService;

    public LocationController(LocationService locationService) {
        this.locationService = locationService;
    }

    @PostMapping
    @PreAuthorize("@orgSecurity.canManageOrgContent(#orgId, principal)")
    public ResponseEntity<LocationResponseDto> create(
            @PathVariable Integer orgId,
            @RequestBody CreateLocationDto dto
    ) {
        Location loc = locationService.create(orgId, dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(LocationResponseDto.from(loc));
    }

    @GetMapping
    @PreAuthorize("@orgSecurity.canReadOrgContent(#orgId, principal)")
    public ResponseEntity<List<LocationResponseDto>> list(@PathVariable Integer orgId) {
        List<LocationResponseDto> out = locationService.listAll(orgId).stream()
                .map(LocationResponseDto::from)
                .toList();
        return ResponseEntity.ok(out);
    }

    @GetMapping("/tree")
    @PreAuthorize("@orgSecurity.canReadOrgContent(#orgId, principal)")
    public ResponseEntity<List<LocationTreeNodeDto>> tree(@PathVariable Integer orgId) {
        return ResponseEntity.ok(locationService.tree(orgId));
    }

    @GetMapping("/{locationId}")
    @PreAuthorize("@orgSecurity.canReadOrgContent(#orgId, principal)")
    public ResponseEntity<LocationResponseDto> getOne(
            @PathVariable Integer orgId,
            @PathVariable Integer locationId
    ) {
        Location loc = locationService.findInOrg(orgId, locationId);
        return ResponseEntity.ok(LocationResponseDto.from(loc, locationService.breadcrumb(loc)));
    }

    @PatchMapping("/{locationId}")
    @PreAuthorize("@orgSecurity.canManageOrgContent(#orgId, principal)")
    public ResponseEntity<LocationResponseDto> update(
            @PathVariable Integer orgId,
            @PathVariable Integer locationId,
            @RequestBody UpdateLocationDto dto
    ) {
        Location loc = locationService.update(orgId, locationId, dto);
        return ResponseEntity.ok(LocationResponseDto.from(loc, locationService.breadcrumb(loc)));
    }

    @DeleteMapping("/{locationId}")
    @PreAuthorize("@orgSecurity.canManageOrgContent(#orgId, principal)")
    public ResponseEntity<Void> delete(
            @PathVariable Integer orgId,
            @PathVariable Integer locationId
    ) {
        locationService.softDelete(orgId, locationId);
        return ResponseEntity.noContent().build();
    }
}
