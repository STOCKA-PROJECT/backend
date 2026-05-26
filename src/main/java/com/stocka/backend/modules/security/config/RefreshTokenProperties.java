package com.stocka.backend.modules.security.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Refresh-token configuration bound to the {@code security.refresh} prefix in
 * {@code application.properties}. Kept as a separate type so {@link
 * com.stocka.backend.modules.auth.service.RefreshTokenService} can stay focused
 * on domain logic instead of {@code @Value} plumbing.
 */
@ConfigurationProperties(prefix = "security.refresh")
public class RefreshTokenProperties {

    /** TTL for sessions without "remember me" (browser-close clears the cookie too). */
    private int sessionTtlDays = 7;

    /** TTL for sessions with "remember me" enabled — persistent cookie. */
    private int rememberTtlDays = 30;

    /** Cookie name. */
    private String cookieName = "stocka_refresh";

    /** Cookie path. Scoped to {@code /auth} so the cookie never travels with API calls. */
    private String cookiePath = "/auth";

    /** Whether the cookie must be marked {@code Secure}. Always true in prod. */
    private boolean cookieSecure = true;

    /** SameSite attribute: {@code Lax}, {@code Strict} or {@code None}. */
    private String cookieSameSite = "Lax";

    /** Optional cookie {@code Domain} attribute. Empty defaults to the request host. */
    private String cookieDomain = "";

    public int getSessionTtlDays() {
        return sessionTtlDays;
    }

    public void setSessionTtlDays(int sessionTtlDays) {
        this.sessionTtlDays = sessionTtlDays;
    }

    public int getRememberTtlDays() {
        return rememberTtlDays;
    }

    public void setRememberTtlDays(int rememberTtlDays) {
        this.rememberTtlDays = rememberTtlDays;
    }

    public String getCookieName() {
        return cookieName;
    }

    public void setCookieName(String cookieName) {
        this.cookieName = cookieName;
    }

    public String getCookiePath() {
        return cookiePath;
    }

    public void setCookiePath(String cookiePath) {
        this.cookiePath = cookiePath;
    }

    public boolean isCookieSecure() {
        return cookieSecure;
    }

    public void setCookieSecure(boolean cookieSecure) {
        this.cookieSecure = cookieSecure;
    }

    public String getCookieSameSite() {
        return cookieSameSite;
    }

    public void setCookieSameSite(String cookieSameSite) {
        this.cookieSameSite = cookieSameSite;
    }

    public String getCookieDomain() {
        return cookieDomain;
    }

    public void setCookieDomain(String cookieDomain) {
        this.cookieDomain = cookieDomain == null ? "" : cookieDomain;
    }
}
