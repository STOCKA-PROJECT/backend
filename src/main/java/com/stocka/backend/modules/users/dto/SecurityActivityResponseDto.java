package com.stocka.backend.modules.users.dto;

import java.time.LocalDateTime;

import com.stocka.backend.modules.security.audit.SecurityEventType;
import com.stocka.backend.modules.security.entity.SecurityAuditEntry;

/**
 * Read-only projection of {@link SecurityAuditEntry} exposed by
 * {@code GET /users/me/security/activity}. The {@code ipAddress} is sent
 * verbatim — the frontend masks the last octet before display to avoid
 * leaking full IPs into the DOM.
 */
public record SecurityActivityResponseDto(
        Long id,
        SecurityEventType eventType,
        boolean success,
        String ipAddress,
        String userAgent,
        String metadata,
        LocalDateTime createdAt
) {

    public static SecurityActivityResponseDto from(SecurityAuditEntry entry) {
        return new SecurityActivityResponseDto(
                entry.getId(),
                entry.getEventType(),
                entry.isSuccess(),
                entry.getIpAddress(),
                entry.getUserAgent(),
                entry.getMetadata(),
                entry.getCreatedAt()
        );
    }
}
