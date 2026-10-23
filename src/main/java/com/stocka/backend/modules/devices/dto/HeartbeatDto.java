package com.stocka.backend.modules.devices.dto;

import java.util.List;

import com.stocka.backend.modules.devices.entity.DeviceState;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /player/heartbeat}, sent by the player with {@code Authorization: Device
 * <token>}. Only {@code deviceId} and {@code state} are required; the rest describes what the player
 * is doing right now and replaces the previous report wholesale.
 *
 * <p>{@code name}, {@code ssid} and {@code ip} are accepted for forward compatibility but not
 * persisted: the device name is managed from Stocka and the recorded IP is the public address the
 * request came from. Unknown JSON properties are ignored.
 *
 * @param deviceId  uuid the player generated for itself (bound on first heartbeat)
 * @param name      hostname or label the player uses locally (ignored)
 * @param state     player state, one of {@link DeviceState}
 * @param showHash  hash of the active show package ({@code sha256:...}) or {@code null}
 * @param showName  name of the active show package or {@code null}
 * @param showTimelineId Stocka timeline the active show was exported from ({@code meta.timelineId}) or {@code null}
 * @param overrides port names currently cut by hand on the player
 * @param version   player software version
 * @param uptimeS   seconds since the player booted
 * @param netMode   network mode ({@code ap}, {@code auto}...)
 * @param ssid      Wi-Fi network the player is on (ignored)
 * @param ip        LAN address the player reports (ignored)
 * @param error     last error message, when {@code state} is ERROR or SAFE
 */
public record HeartbeatDto(
        @NotBlank @Size(max = 36) String deviceId,
        @Size(max = 80) String name,
        @NotBlank @Pattern(regexp = DeviceState.PATTERN) String state,
        @Size(max = 80) String showHash,
        @Size(max = 160) String showName,
        @Positive Integer showTimelineId,
        @Size(max = 200) List<@Size(max = 160) String> overrides,
        @Size(max = 40) String version,
        @PositiveOrZero Long uptimeS,
        @Size(max = 8) String netMode,
        @Size(max = 64) String ssid,
        @Size(max = 45) String ip,
        @Size(max = 4000) String error,
        @Valid @Size(max = 20) List<DeviceAlertDto> alerts
) { }
