package com.stocka.backend.modules.devices.dto;

import java.time.LocalDate;
import java.util.Map;

/**
 * How far each log file of a player is stored in Stocka ({@code GET /player/logs/cursor} and
 * {@code GET /devices/{uuid}/logs/cursor}): the uploader sends each file from its end offset on.
 *
 * @param files end offset in bytes by file name; a missing file has nothing stored
 * @param since first day the retention keeps: older files are not worth uploading
 */
public record DeviceLogCursorDto(Map<String, Long> files, LocalDate since) {
}
