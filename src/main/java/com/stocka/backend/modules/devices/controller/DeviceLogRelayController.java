package com.stocka.backend.modules.devices.controller;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.stocka.backend.modules.common.error.ApiException;
import com.stocka.backend.modules.common.error.ErrorCodes;
import com.stocka.backend.modules.devices.dto.DeviceLogAppendDto;
import com.stocka.backend.modules.devices.dto.DeviceLogCursorDto;
import com.stocka.backend.modules.devices.entity.Device;
import com.stocka.backend.modules.devices.entity.DeviceLogChunk.Source;
import com.stocka.backend.modules.devices.repository.DeviceRepository;
import com.stocka.backend.modules.devices.service.DeviceLogService;
import com.stocka.backend.modules.devices.service.DeviceLogService.AppendResult;
import com.stocka.backend.modules.devices.service.DeviceLogService.Cursor;
import com.stocka.backend.modules.organizations.security.OrganizationSecurity;
import com.stocka.backend.modules.users.entity.User;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The belén app carries a player's log when the player has no Internet: it reads the new bytes on
 * the local network, keeps them on the phone and, once online, asks how far each file is stored
 * ({@code GET /devices/{deviceUuid}/logs/cursor}) and sends the rest ({@code POST
 * /devices/{deviceUuid}/logs?file=&from=}, raw bytes). The app only knows the uuid the player
 * generated for itself ({@code GET /api/info}), so the device is looked up by it among the
 * organizations where the user may control devices ({@code canControlDevices}: not SPECTATOR); a
 * player that never reached Stocka (no heartbeat, never seen by the editor) is 404 and the app keeps
 * the log for later.
 */
@RestController
@RequestMapping("/devices/{deviceUuid}/logs")
public class DeviceLogRelayController {
    private final DeviceRepository deviceRepository;
    private final DeviceLogService logService;
    private final OrganizationSecurity orgSecurity;

    public DeviceLogRelayController(DeviceRepository deviceRepository, DeviceLogService logService, OrganizationSecurity orgSecurity) {
        this.deviceRepository = deviceRepository;
        this.logService = logService;
        this.orgSecurity = orgSecurity;
    }

    /**
     * How far each log file of the player is stored.
     *
     * @return 200; 404 {@code devices.not_found} when no device the user controls has the uuid
     */
    @GetMapping("/cursor")
    public ResponseEntity<DeviceLogCursorDto> cursor(@PathVariable String deviceUuid, @AuthenticationPrincipal Object principal) {
        Cursor cursor = logService.cursor(controllable(deviceUuid, principal));
        return ResponseEntity.ok(new DeviceLogCursorDto(cursor.files(), cursor.since()));
    }

    /**
     * Stores the bytes {@code [from, from + body length)} of a log file of the player.
     *
     * @return 200 with the end offset now stored; 404 as {@link #cursor}; 400 / 409 / 413 as
     *         {@link DeviceLogService#append}
     * @throws IOException when the body cannot be read
     */
    @PostMapping
    public ResponseEntity<DeviceLogAppendDto> append(
            @PathVariable String deviceUuid,
            @RequestParam String file,
            @RequestParam long from,
            @AuthenticationPrincipal Object principal,
            HttpServletRequest request
    ) throws IOException {
        Device device = controllable(deviceUuid, principal);
        byte[] bytes = logService.readUpload(request.getInputStream());
        Integer userId = principal instanceof User user ? user.getId() : null;
        AppendResult result = logService.append(device, file, from, bytes, Source.USER, userId);
        return ResponseEntity.ok(new DeviceLogAppendDto(result.file(), result.end(), result.stored()));
    }

    private Device controllable(String deviceUuid, Object principal) {
        return deviceRepository.findByDeviceId(deviceUuid).stream()
                .filter(device -> orgSecurity.canControlDevices(device.getOrganization().getSlug(), principal))
                .findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.DEVICES_NOT_FOUND));
    }
}
