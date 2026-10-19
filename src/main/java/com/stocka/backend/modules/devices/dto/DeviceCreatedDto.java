package com.stocka.backend.modules.devices.dto;

/**
 * Response of device creation and token rotation: the device plus the raw heartbeat token, which is
 * returned exactly once (only its hash is stored), and the URL the player must POST to.
 *
 * @param device         the registered device
 * @param heartbeatToken raw token to configure on the player ({@code bht_...})
 * @param heartbeatUrl   absolute URL of {@code POST /player/heartbeat} on this backend
 */
public record DeviceCreatedDto(
        DeviceResponseDto device,
        String heartbeatToken,
        String heartbeatUrl
) { }
