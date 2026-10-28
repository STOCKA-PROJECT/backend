package com.stocka.backend.modules.devices.controller;

import java.io.IOException;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.stocka.backend.modules.devices.dto.DeviceLogAppendDto;
import com.stocka.backend.modules.devices.dto.DeviceLogCursorDto;
import com.stocka.backend.modules.devices.entity.Device;
import com.stocka.backend.modules.devices.entity.DeviceLogChunk.Source;
import com.stocka.backend.modules.devices.service.DeviceLogService;
import com.stocka.backend.modules.devices.service.DeviceLogService.AppendResult;
import com.stocka.backend.modules.devices.service.DeviceLogService.Cursor;
import com.stocka.backend.modules.devices.service.DeviceService;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The player uploads its own log whenever it has Internet, with {@code Authorization: Device <token>}
 * like the heartbeat: it asks how far each file is stored ({@code GET /player/logs/cursor}) and sends
 * the rest piece by piece ({@code POST /player/logs?file=&from=}, the raw bytes as the body). Public
 * in the REST chain (the token is checked here) and rate limited per IP ({@code player.logs.ip}).
 */
@RestController
@RequestMapping("/player/logs")
public class PlayerLogController {
    private final DeviceService deviceService;
    private final DeviceLogService logService;

    public PlayerLogController(DeviceService deviceService, DeviceLogService logService) {
        this.deviceService = deviceService;
        this.logService = logService;
    }

    /**
     * How far each log file of the calling player is stored.
     *
     * @return 200 with the end offset per file; 401 {@code devices.token_invalid}
     */
    @GetMapping("/cursor")
    public ResponseEntity<DeviceLogCursorDto> cursor(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization
    ) {
        Device device = deviceService.authenticate(authorization);
        Cursor cursor = logService.cursor(device);
        return ResponseEntity.ok(new DeviceLogCursorDto(cursor.files(), cursor.since()));
    }

    /**
     * Stores the bytes {@code [from, from + body length)} of a log file of the calling player.
     *
     * @return 200 with the end offset now stored; 401 for a bad token; 400 / 409 / 413 as
     *         {@link DeviceLogService#append}
     * @throws IOException when the body cannot be read
     */
    @PostMapping
    public ResponseEntity<DeviceLogAppendDto> append(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam String file,
            @RequestParam long from,
            HttpServletRequest request
    ) throws IOException {
        Device device = deviceService.authenticate(authorization);
        byte[] bytes = logService.readUpload(request.getInputStream());
        AppendResult result = logService.append(device, file, from, bytes, Source.DEVICE, null);
        return ResponseEntity.ok(new DeviceLogAppendDto(result.file(), result.end(), result.stored()));
    }
}
