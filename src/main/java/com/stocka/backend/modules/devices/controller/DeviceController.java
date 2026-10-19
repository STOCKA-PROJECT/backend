package com.stocka.backend.modules.devices.controller;

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

import com.stocka.backend.modules.devices.dto.DeviceCreatedDto;
import com.stocka.backend.modules.devices.dto.DeviceResponseDto;
import com.stocka.backend.modules.devices.dto.UpsertDeviceDto;
import com.stocka.backend.modules.devices.entity.Device;
import com.stocka.backend.modules.devices.service.DeviceService;
import com.stocka.backend.modules.devices.service.DeviceService.DeviceWithToken;
import com.stocka.backend.modules.devices.service.HeartbeatUrlResolver;
import com.stocka.backend.modules.organizations.service.OrganizationResolver;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * REST resource exposing an organization's registered players ({@link Device}): their state as
 * last reported by heartbeat, and the management of their heartbeat tokens.
 *
 * <p>Same gating as ports: only organizations whose owner is a global admin have the feature;
 * any member of such an organization may read, OWNER or MANAGER may create, rename, delete and
 * rotate tokens ({@code @orgSecurity.canReadPorts} / {@code canManagePorts}).
 */
@RestController
@RequestMapping("/organizations/{orgSlug}/devices")
public class DeviceController {
    private final DeviceService deviceService;
    private final OrganizationResolver orgResolver;
    private final HeartbeatUrlResolver heartbeatUrlResolver;

    public DeviceController(
            DeviceService deviceService,
            OrganizationResolver orgResolver,
            HeartbeatUrlResolver heartbeatUrlResolver
    ) {
        this.deviceService = deviceService;
        this.orgResolver = orgResolver;
        this.heartbeatUrlResolver = heartbeatUrlResolver;
    }

    @GetMapping
    @PreAuthorize("@orgSecurity.canReadPorts(#orgSlug, principal)")
    public ResponseEntity<List<DeviceResponseDto>> list(@PathVariable String orgSlug) {
        Integer orgId = orgResolver.requireCurrent(orgSlug).getId();
        List<DeviceResponseDto> out = deviceService.listAll(orgId).stream()
                .map(deviceService::toResponse)
                .toList();
        return ResponseEntity.ok(out);
    }

    @PostMapping
    @PreAuthorize("@orgSecurity.canManagePorts(#orgSlug, principal)")
    public ResponseEntity<DeviceCreatedDto> create(
            @PathVariable String orgSlug,
            @Valid @RequestBody UpsertDeviceDto dto,
            HttpServletRequest request
    ) {
        Integer orgId = orgResolver.requireCurrent(orgSlug).getId();
        DeviceWithToken created = deviceService.create(orgId, dto.name());
        return ResponseEntity.status(HttpStatus.CREATED).body(toCreated(created, request));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("@orgSecurity.canManagePorts(#orgSlug, principal)")
    public ResponseEntity<DeviceResponseDto> rename(
            @PathVariable String orgSlug,
            @PathVariable Integer id,
            @Valid @RequestBody UpsertDeviceDto dto
    ) {
        Integer orgId = orgResolver.requireCurrent(orgSlug).getId();
        Device device = deviceService.rename(orgId, id, dto.name());
        return ResponseEntity.ok(deviceService.toResponse(device));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@orgSecurity.canManagePorts(#orgSlug, principal)")
    public ResponseEntity<Void> delete(
            @PathVariable String orgSlug,
            @PathVariable Integer id
    ) {
        Integer orgId = orgResolver.requireCurrent(orgSlug).getId();
        deviceService.softDelete(orgId, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/rotate-token")
    @PreAuthorize("@orgSecurity.canManagePorts(#orgSlug, principal)")
    public ResponseEntity<DeviceCreatedDto> rotateToken(
            @PathVariable String orgSlug,
            @PathVariable Integer id,
            HttpServletRequest request
    ) {
        Integer orgId = orgResolver.requireCurrent(orgSlug).getId();
        DeviceWithToken rotated = deviceService.rotateToken(orgId, id);
        return ResponseEntity.ok(toCreated(rotated, request));
    }

    private DeviceCreatedDto toCreated(DeviceWithToken result, HttpServletRequest request) {
        return new DeviceCreatedDto(
                deviceService.toResponse(result.device()),
                result.token(),
                heartbeatUrlResolver.resolve(request));
    }
}
