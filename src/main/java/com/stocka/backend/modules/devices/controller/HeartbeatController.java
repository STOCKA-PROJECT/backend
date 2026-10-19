package com.stocka.backend.modules.devices.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.stocka.backend.modules.common.error.ApiException;
import com.stocka.backend.modules.common.error.ErrorCodes;
import com.stocka.backend.modules.devices.dto.HeartbeatDto;
import com.stocka.backend.modules.devices.service.DeviceService;
import com.stocka.backend.modules.security.ratelimit.ClientIpResolver;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * Public endpoint the belén players call whenever they have Internet: {@code POST /player/heartbeat}
 * with {@code Authorization: Device <token>}. It is outside the JWT/OAuth schemes on purpose: the
 * token identifies a device, not a user, and grants nothing else. Rate limited per IP by
 * {@code RateLimitFilter} ({@code player.heartbeat.ip}).
 */
@RestController
@RequestMapping("/player")
public class HeartbeatController {
    private final DeviceService deviceService;
    private final ClientIpResolver clientIpResolver;

    public HeartbeatController(DeviceService deviceService, ClientIpResolver clientIpResolver) {
        this.deviceService = deviceService;
        this.clientIpResolver = clientIpResolver;
    }

    @PostMapping("/heartbeat")
    public ResponseEntity<Void> heartbeat(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @Valid @RequestBody HeartbeatDto dto,
            HttpServletRequest request
    ) {
        String token = DeviceService.extractDeviceToken(authorization)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, ErrorCodes.DEVICES_TOKEN_INVALID));
        deviceService.recordHeartbeat(token, dto, clientIpResolver.resolve(request));
        return ResponseEntity.noContent().build();
    }
}
