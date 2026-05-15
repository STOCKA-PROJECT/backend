package com.stocka.backend.modules.notifications.email;

/**
 * Utility helpers for values that are used as email headers
 * ({@code From}, {@code To}, {@code Reply-To}, {@code Subject}, ...).
 *
 * <p>Centralizes the defensive treatment that must be applied to any
 * user-supplied input before it reaches the SMTP/HTTP layer, so the same
 * sanitization is enforced by every provider.
 */
final class EmailHeaders {

    private EmailHeaders() {
    }

    /**
     * Sanitize a value before using it as an email header.
     *
     * <p>Strips CR/LF characters by replacing them with a single space and
     * trims the result, so an attacker cannot smuggle additional headers
     * (e.g. {@code Bcc:}) through user-controlled fields such as the display
     * name embedded in a localized subject.
     *
     * @param s raw header value (may be {@code null})
     * @return sanitized header value, or {@code null} when the input is {@code null}
     */
    static String safeHeader(String s) {
        return s == null ? null : s.replaceAll("[\\r\\n]", " ").trim();
    }
}
