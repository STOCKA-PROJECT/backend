package com.stocka.backend.modules.devices.dto;

import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;

import com.stocka.backend.modules.devices.entity.Device;

/**
 * A registered player as exposed in REST responses. Everything prefixed {@code last} comes from the
 * latest heartbeat and is {@code null} until the first one arrives; {@code online} is true when that
 * heartbeat is within the configured window. {@code tokenPrefix} is the only part of the heartbeat
 * token kept in clear. {@code pin} is only filled for callers who may send shows (it is {@code null}
 * otherwise, while {@code hasPin} still says whether one is saved).
 *
 * @param id            Stocka id of the device row
 * @param deviceId      uuid the player generated for itself ({@code null} until it reports)
 * @param name          display name, unique per organization
 * @param board         board identifier ({@code rpi4b})
 * @param localUrl      address of the player on the local network, or {@code null}
 * @param pin           the player's PIN, or {@code null} when none is saved or the caller may not see it
 * @param hasPin        whether a PIN is saved
 * @param online        whether the last heartbeat is recent enough
 * @param lastSeenAt    time of the last heartbeat
 * @param lastState     player state (BOOT, SAFE, IDLE, PLAYING, TEST, ERROR)
 * @param lastShowHash  hash of the active show package
 * @param lastShowName  name of the active show package
 * @param lastShowTimelineId Stocka timeline the active show was exported from
 * @param lastOverrides port names cut by hand on the player
 * @param lastAlerts    alerts in the last heartbeat (motor timeouts, sensors that stopped the show)
 * @param lastVersion   player software version
 * @param lastIp        public IP the heartbeat came from
 * @param lastError     last error message reported by the player
 * @param tokenPrefix   first characters of the heartbeat token
 * @param createdAt     registration time
 */
public record DeviceResponseDto(
        Integer id,
        String deviceId,
        String name,
        String board,
        String localUrl,
        String pin,
        boolean hasPin,
        boolean online,
        LocalDateTime lastSeenAt,
        String lastState,
        String lastShowHash,
        String lastShowName,
        Integer lastShowTimelineId,
        List<String> lastOverrides,
        List<DeviceAlertDto> lastAlerts,
        String lastVersion,
        String lastIp,
        String lastError,
        String tokenPrefix,
        Date createdAt
) {
    /**
     * Builds the response from a {@link Device} and its already-computed derived values.
     *
     * @param device    source device
     * @param online    whether the device counts as online now
     * @param overrides deserialized override list
     * @param alerts    deserialized alert list
     * @param revealPin whether the caller may see the PIN
     * @return the populated response DTO
     */
    public static DeviceResponseDto from(Device device, boolean online, List<String> overrides, List<DeviceAlertDto> alerts,
                                         boolean revealPin) {
        return new DeviceResponseDto(
                device.getId(),
                device.getDeviceId(),
                device.getName(),
                device.getBoard(),
                device.getLocalUrl(),
                revealPin ? device.getPin() : null,
                device.getPin() != null,
                online,
                device.getLastSeenAt(),
                device.getLastState(),
                device.getLastShowHash(),
                device.getLastShowName(),
                device.getLastShowTimelineId(),
                overrides,
                alerts,
                device.getLastVersion(),
                device.getLastIp(),
                device.getLastError(),
                device.getTokenPrefix(),
                device.getCreatedAt()
        );
    }
}
