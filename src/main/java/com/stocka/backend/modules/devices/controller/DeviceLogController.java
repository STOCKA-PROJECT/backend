package com.stocka.backend.modules.devices.controller;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.stocka.backend.modules.devices.dto.DeviceLogFileDto;
import com.stocka.backend.modules.devices.entity.Device;
import com.stocka.backend.modules.devices.entity.DeviceLogChunk;
import com.stocka.backend.modules.devices.service.DeviceLogService;
import com.stocka.backend.modules.devices.service.DeviceService;
import com.stocka.backend.modules.organizations.service.OrganizationResolver;

/**
 * The log of a player kept in Stocka, for the device page of the frontend: the stored files
 * ({@code GET .../logs}), one file or its last lines ({@code GET .../logs/{file}?tail=}, as an
 * attachment with {@code download=true}) and a zip of a period ({@code GET .../logs/archive?from=&to=}).
 * Any member of the organization may read it, with the same gating as the device list
 * ({@code @orgSecurity.canReadPorts}).
 */
@RestController
@RequestMapping("/organizations/{orgSlug}/devices/{id}/logs")
public class DeviceLogController {
    private static final MediaType TEXT_UTF8 = new MediaType("text", "plain", StandardCharsets.UTF_8);
    private static final MediaType APPLICATION_ZIP = MediaType.parseMediaType("application/zip");

    private final DeviceService deviceService;
    private final DeviceLogService logService;
    private final OrganizationResolver orgResolver;

    public DeviceLogController(DeviceService deviceService, DeviceLogService logService, OrganizationResolver orgResolver) {
        this.deviceService = deviceService;
        this.logService = logService;
        this.orgResolver = orgResolver;
    }

    /**
     * The log files of the device kept in Stocka, newest day first.
     *
     * @return 200 (empty when nothing arrived yet); 404 {@code devices.not_found}
     */
    @GetMapping
    @PreAuthorize("@orgSecurity.canReadPorts(#orgSlug, principal)")
    public ResponseEntity<List<DeviceLogFileDto>> files(@PathVariable String orgSlug, @PathVariable Integer id) {
        Device device = device(orgSlug, id);
        List<DeviceLogFileDto> out = logService.files(device).stream()
                .map(f -> new DeviceLogFileDto(f.getFileName(), f.getLogDay(), nz(f.getSize()), nz(f.getLines()),
                        f.getFirstAt(), f.getLastAt(), f.getSyncedAt()))
                .toList();
        return ResponseEntity.ok(out);
    }

    /**
     * A zip with the log files of a period, one entry per file.
     *
     * @return 200 {@code application/zip} as an attachment; 400 {@code devices.log_range_invalid};
     *         404 when nothing is stored in the period
     */
    @GetMapping("/archive")
    @PreAuthorize("@orgSecurity.canReadPorts(#orgSlug, principal)")
    public ResponseEntity<StreamingResponseBody> archive(
            @PathVariable String orgSlug,
            @PathVariable Integer id,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        Device device = device(orgSlug, id);
        List<DeviceLogChunk> chunks = logService.periodChunks(device, from, to);
        StreamingResponseBody body = out -> logService.writeArchive(chunks, out);
        return ResponseEntity.ok()
                .contentType(APPLICATION_ZIP)
                .header(HttpHeaders.CONTENT_DISPOSITION, attachment(device.getName() + "-" + from + "_" + to + ".zip"))
                .body(body);
    }

    /**
     * One log file as the player wrote it, or its last {@code tail} lines.
     *
     * @return 200 {@code text/plain; charset=UTF-8}; 400 {@code devices.log_file_invalid}; 404
     *         {@code devices.log_not_found}
     */
    @GetMapping("/{file}")
    @PreAuthorize("@orgSecurity.canReadPorts(#orgSlug, principal)")
    public ResponseEntity<StreamingResponseBody> file(
            @PathVariable String orgSlug,
            @PathVariable Integer id,
            @PathVariable String file,
            @RequestParam(required = false) Integer tail,
            @RequestParam(defaultValue = "false") boolean download
    ) {
        Device device = device(orgSlug, id);
        List<DeviceLogChunk> chunks = logService.fileChunks(device, file);
        StreamingResponseBody body = out -> logService.writeFile(chunks, tail, out);
        ResponseEntity.BodyBuilder response = ResponseEntity.ok().contentType(TEXT_UTF8);
        if (download) {
            response.header(HttpHeaders.CONTENT_DISPOSITION, attachment(device.getName() + "-" + file));
        }
        return response.body(body);
    }

    private Device device(String orgSlug, Integer id) {
        return deviceService.findInOrg(orgResolver.requireCurrent(orgSlug).getId(), id);
    }

    private static String attachment(String fileName) {
        return ContentDisposition.attachment().filename(fileName.replace('/', '_'), StandardCharsets.UTF_8).build().toString();
    }

    private static long nz(Long value) {
        return value == null ? 0 : value;
    }
}
