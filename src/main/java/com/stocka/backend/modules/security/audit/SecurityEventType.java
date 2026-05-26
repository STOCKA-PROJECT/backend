package com.stocka.backend.modules.security.audit;

/**
 * Catalog of events recorded in the security audit log. Names match the
 * {@code event_type} column verbatim (snake_case in the DB, UPPER_SNAKE_CASE
 * here per Java conventions). Adding a new value requires:
 * <ol>
 *   <li>An i18n label in {@code messages_{es,ca,en}.properties} under
 *       {@code security.audit.event.<value>}.</li>
 *   <li>An icon mapping in {@code SecurityEventIcon.vue}.</li>
 * </ol>
 */
public enum SecurityEventType {
    /** Successful credentials + email-verified login. */
    LOGIN_SUCCESS,
    /** Wrong password or unknown email. */
    LOGIN_FAILED,
    /** Explicit logout. */
    LOGOUT,
    /** User changed their own password from the profile screen. */
    PASSWORD_CHANGED,
    /** A password-reset email was requested. */
    PASSWORD_RESET_REQUESTED,
    /** A password-reset token was consumed. */
    PASSWORD_RESET_COMPLETED,
    /** Email-verification link consumed successfully. */
    EMAIL_VERIFIED,
    /** Future use (F2). */
    TWO_FACTOR_ENABLED,
    /** Future use (F2). */
    TWO_FACTOR_DISABLED,
    /** Future use (F2): invalid TOTP / recovery code on the 2FA challenge. */
    TWO_FACTOR_CHALLENGE_FAILED,
    /** Future use (F4). */
    OAUTH_LINKED,
    /** Future use (F4). */
    OAUTH_UNLINKED,
    /** A refresh token was presented twice — the whole family was wiped. */
    REFRESH_REUSE_DETECTED,
    /** Future use (F6): first login from a previously-unseen device. */
    NEW_DEVICE_LOGIN,
    /** Future use (F6): the user revoked a session from the panel. */
    SESSION_REVOKED
}
