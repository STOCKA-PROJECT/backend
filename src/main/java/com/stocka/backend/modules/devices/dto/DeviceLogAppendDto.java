package com.stocka.backend.modules.devices.dto;

/**
 * Answer to an uploaded piece of a log file ({@code POST /player/logs},
 * {@code POST /devices/{uuid}/logs}).
 *
 * @param file   file name
 * @param end    end offset in bytes the backend now has for the file: the next piece starts there
 * @param stored bytes of this upload that were new (0 when all of it was already stored)
 */
public record DeviceLogAppendDto(String file, long end, long stored) {
}
