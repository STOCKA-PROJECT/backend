package com.stocka.backend.modules.devices.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.stocka.backend.modules.devices.dto.CreateDeviceDto;
import com.stocka.backend.modules.devices.dto.DeviceConnectionDto;
import com.stocka.backend.modules.devices.dto.DeviceCreatedDto;
import com.stocka.backend.modules.devices.dto.DeviceResponseDto;
import com.stocka.backend.modules.devices.dto.UpdateDeviceDto;
import com.stocka.backend.modules.devices.entity.Device;
import com.stocka.backend.modules.devices.service.DeviceService;
import com.stocka.backend.modules.devices.service.DeviceService.DeviceWithToken;
import com.stocka.backend.modules.devices.service.HeartbeatUrlResolver;
import com.stocka.backend.modules.organizations.security.OrganizationSecurity;
import com.stocka.backend.modules.organizations.service.OrganizationResolver;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * REST resource exposing an organization's registered players ({@link Device}): their state as
 * last reported by heartbeat, and the management of their heartbeat tokens.
 *
 * <p>Same gating as ports: only organizations whose owner is a global admin have the feature;
 * any member of such an organization may read, OWNER or MANAGER may create, rename, delete and
 * rotate tokens ({@code @orgSecurity.canReadPorts} / {@code canManagePorts}). The PIN of each device
 * is only shown to who may send shows ({@code canControlDevices}: not SPECTATOR), who may also save
 * the address and PIN the editor used to reach it ({@code PATCH .../{id}/connection}).
 */
@RestController
@RequestMapping("/organizations/{orgSlug}/devices")
public class DeviceController {
    private final DeviceService deviceService;
    private final OrganizationResolver orgResolver;
    private final HeartbeatUrlResolver heartbeatUrlResolver;
    private final OrganizationSecurity orgSecurity;

    public DeviceController(
            DeviceService deviceService,
            OrganizationResolver orgResolver,
            HeartbeatUrlResolver heartbeatUrlResolver,
            OrganizationSecurity orgSecurity
    ) {
        this.deviceService = deviceService;
        this.orgResolver = orgResolver;
        this.heartbeatUrlResolver = heartbeatUrlResolver;
        this.orgSecurity = orgSecurity;
    }

    @GetMapping
    @PreAuthorize("@orgSecurity.canReadPorts(#orgSlug, principal)")
    public ResponseEntity<List<DeviceResponseDto>> list(
            @PathVariable String orgSlug,
            @AuthenticationPrincipal Object principal
    ) {
        Integer orgId = orgResolver.requireCurrent(orgSlug).getId();
        boolean revealPin = orgSecurity.canControlDevices(orgSlug, principal);
        List<DeviceResponseDto> out = deviceService.listAll(orgId).stream()
                .map(device -> deviceService.toResponse(device, revealPin))
                .toList();
        return ResponseEntity.ok(out);
    }

    @PostMapping
    @PreAuthorize("@orgSecurity.canManagePorts(#orgSlug, principal)")
    public ResponseEntity<DeviceCreatedDto> create(
            @PathVariable String orgSlug,
            @Valid @RequestBody CreateDeviceDto dto,
            HttpServletRequest request
    ) {
        Integer orgId = orgResolver.requireCurrent(orgSlug).getId();
        DeviceWithToken created = deviceService.create(orgId, dto.name(), dto.localUrl(), dto.pin());
        return ResponseEntity.status(HttpStatus.CREATED).body(toCreated(created, request));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("@orgSecurity.canManagePorts(#orgSlug, principal)")
    public ResponseEntity<DeviceResponseDto> update(
            @PathVariable String orgSlug,
            @PathVariable Integer id,
            @Valid @RequestBody UpdateDeviceDto dto
    ) {
        Integer orgId = orgResolver.requireCurrent(orgSlug).getId();
        Device device = deviceService.update(orgId, id, dto.name(), dto.localUrl(), dto.pin());
        return ResponseEntity.ok(deviceService.toResponse(device, true));
    }

    @PatchMapping("/{id}/connection")
    @PreAuthorize("@orgSecurity.canControlDevices(#orgSlug, principal)")
    public ResponseEntity<DeviceResponseDto> updateConnection(
            @PathVariable String orgSlug,
            @PathVariable Integer id,
            @Valid @RequestBody DeviceConnectionDto dto
    ) {
        Integer orgId = orgResolver.requireCurrent(orgSlug).getId();
        Device device = deviceService.updateConnection(orgId, id, dto.localUrl(), dto.pin(), dto.deviceId());
        return ResponseEntity.ok(deviceService.toResponse(device, true));
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
                deviceService.toResponse(result.device(), true),
                result.token(),
                heartbeatUrlResolver.resolve(request));
    }
}
