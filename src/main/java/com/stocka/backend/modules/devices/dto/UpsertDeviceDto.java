package com.stocka.backend.modules.devices.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /organizations/{orgSlug}/devices} and {@code PATCH .../devices/{id}}: the
 * only user-editable field of a device is its display name, trimmed by the service and unique per
 * organization.
 *
 * @param name display name, 1–80 characters after trimming
 */
public record UpsertDeviceDto(
        @NotBlank @Size(max = 80) String name
) { }
