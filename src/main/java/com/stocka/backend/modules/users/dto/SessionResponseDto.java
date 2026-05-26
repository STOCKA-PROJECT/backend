package com.stocka.backend.modules.users.dto;

import java.time.LocalDateTime;

import com.stocka.backend.modules.users.entity.UserDevice;

/**
 * Public projection of {@link UserDevice}. The IP travels as-is — the
 * frontend's {@code SessionCard.vue} masks it before display, matching the
 * audit-log convention.
 *
 * @param id stable identifier exposed to the panel (path param of revoke/rename)
 * @param displayName human-friendly label, editable via PATCH
 * @param userAgent raw User-Agent header from the original login (truncated to 512 chars)
 * @param lastIp client IP at the latest activity (login or refresh)
 * @param firstSeenAt timestamp of the login that started the session
 * @param lastSeenAt timestamp of the latest /auth/refresh round-trip
 * @param current {@code true} when the requesting access token was minted from
 *                this device's refresh-token family
 */
public record SessionResponseDto(
        Long id,
        String displayName,
        String userAgent,
        String lastIp,
        LocalDateTime firstSeenAt,
        LocalDateTime lastSeenAt,
        boolean current
) {

    public static SessionResponseDto from(UserDevice device, boolean current) {
        return new SessionResponseDto(
                device.getId(),
                device.getDisplayName(),
                device.getUserAgentRaw(),
                device.getLastIp(),
                device.getFirstSeenAt(),
                device.getLastSeenAt(),
                current
        );
    }
}
