package com.stocka.backend.modules.security.audit;

import com.stocka.backend.modules.users.entity.User;

/**
 * Application event carrying everything the {@link SecurityAuditListener} needs
 * to persist a row. The request-scoped data ({@code ipAddress}, {@code
 * userAgent}) is snapshotted by {@link SecurityAuditService} <em>before</em>
 * publishing, because the listener runs on a different thread and the
 * {@code RequestContextHolder} would no longer be populated there.
 *
 * @param eventType  catalog entry — drives the i18n label and the icon
 * @param userId     authenticated user id, or {@code null} for pre-auth events
 *                   (failed login from an unknown email, password-reset
 *                   request, …). Resolved to {@link User} inside the listener
 *                   to avoid serializing a Hibernate-managed entity across
 *                   threads.
 * @param email      snapshot of the email being authenticated against; useful
 *                   even when {@code userId} is {@code null}
 * @param ipAddress  client IP captured at publish time
 * @param userAgent  client UA captured at publish time
 * @param metadata   optional JSON blob with event-specific extras
 * @param success    {@code true} for successful actions, {@code false} for
 *                   failures (failed login, 2FA challenge failed, reuse
 *                   detected)
 */
public record SecurityAuditEvent(
        SecurityEventType eventType,
        Integer userId,
        String email,
        String ipAddress,
        String userAgent,
        String metadata,
        boolean success
) {
}
