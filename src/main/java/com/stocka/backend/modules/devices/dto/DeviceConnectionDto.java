package com.stocka.backend.modules.devices.dto;

import jakarta.validation.constraints.Size;

/**
 * Body of {@code PATCH /organizations/{orgSlug}/devices/{id}/connection}, what the timeline editor saves
 * after reaching the player on the local network. {@code null} leaves a field as it is; an empty
 * {@code localUrl} or {@code pin} removes it. {@code deviceId} binds the uuid the player answered with,
 * like its first heartbeat would.
 *
 * @param localUrl address the player answered at; empty to remove it
 * @param pin      PIN the player accepted; empty to remove it
 * @param deviceId uuid of the player ({@code device.json}), or {@code null}
 */
public record DeviceConnectionDto(
        @Size(max = 255) String localUrl,
        @Size(max = 16) String pin,
        @Size(max = 36) String deviceId
) { }
