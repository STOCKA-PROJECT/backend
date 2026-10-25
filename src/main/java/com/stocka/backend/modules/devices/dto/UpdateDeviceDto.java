package com.stocka.backend.modules.devices.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code PATCH /organizations/{orgSlug}/devices/{id}}. Every field is optional: {@code null}
 * leaves it as it is, and an empty {@code localUrl} or {@code pin} removes it. A name, when present,
 * must not be blank.
 *
 * @param name     new display name, 1–80 characters after trimming
 * @param localUrl new address on the local network; empty to remove it
 * @param pin      new 6-digit PIN; empty to remove it
 */
public record UpdateDeviceDto(
        @Size(max = 80) @Pattern(regexp = "(?s).*\\S.*") String name,
        @Size(max = 255) String localUrl,
        @Size(max = 16) String pin
) { }
