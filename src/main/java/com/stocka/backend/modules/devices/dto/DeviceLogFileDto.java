package com.stocka.backend.modules.devices.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One log file of a player kept in Stocka ({@code GET /organizations/{orgSlug}/devices/{id}/logs}).
 *
 * @param file     file name, e.g. {@code system-2026-10-26.log}
 * @param day      day of the file
 * @param size     bytes stored, from the start of the file
 * @param lines    complete lines stored
 * @param firstAt  time of the first line (the player's local time), or {@code null}
 * @param lastAt   time of the last line, or {@code null}
 * @param syncedAt when the last piece arrived
 */
public record DeviceLogFileDto(
        String file,
        LocalDate day,
        long size,
        long lines,
        LocalDateTime firstAt,
        LocalDateTime lastAt,
        LocalDateTime syncedAt) {
}
