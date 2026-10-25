package com.stocka.backend.modules.devices.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /organizations/{orgSlug}/devices}. The name is trimmed by the service and unique
 * per organization; the local address and the PIN are optional and normalized by the service.
 *
 * @param name     display name, 1–80 characters after trimming
 * @param localUrl address of the player on the local network ({@code belen.local},
 *                 {@code http://10.42.0.1:8080}...), or {@code null}
 * @param pin      the player's 6-digit PIN, or {@code null}
 */
public record CreateDeviceDto(
        @NotBlank @Size(max = 80) String name,
        @Size(max = 255) String localUrl,
        @Size(max = 16) String pin
) { }
