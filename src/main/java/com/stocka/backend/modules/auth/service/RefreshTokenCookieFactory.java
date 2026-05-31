package com.stocka.backend.modules.auth.service;

import java.time.Duration;
import java.util.Optional;

import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import com.stocka.backend.modules.security.config.RefreshTokenProperties;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Builds the {@code stocka_refresh} {@link ResponseCookie} for issuing and
 * clearing the long-lived session. Centralized so login, refresh and logout
 * stay consistent (path, SameSite, domain, …).
 */
@Component
public class RefreshTokenCookieFactory {

    private final RefreshTokenProperties properties;

    public RefreshTokenCookieFactory(RefreshTokenProperties properties) {
        this.properties = properties;
    }

    /**
     * Builds the {@code Set-Cookie} value for issuing or rotating a refresh
     * token. When {@code rememberMe} is false the cookie is a session cookie
     * (no {@code Max-Age}); the server-side TTL still applies regardless.
     *
     * @param rawToken   raw refresh-token value
     * @param rememberMe whether the cookie should outlive the browser session
     * @return a fully-configured {@link ResponseCookie}
     */
    public ResponseCookie build(String rawToken, boolean rememberMe) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie
                .from(properties.getCookieName(), rawToken)
                .httpOnly(true)
                .secure(properties.isCookieSecure())
                .path(properties.getCookiePath())
                .sameSite(properties.getCookieSameSite());
        if (rememberMe) {
            builder.maxAge(Duration.ofDays(properties.getRememberTtlDays()));
        }
        if (!properties.getCookieDomain().isBlank()) {
            builder.domain(properties.getCookieDomain());
        }
        return builder.build();
    }

    /**
     * Builds an expiration cookie that tells the browser to drop
     * {@code stocka_refresh}. Used by logout.
     *
     * @return a {@link ResponseCookie} with {@code Max-Age=0}
     */
    public ResponseCookie buildClearing() {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie
                .from(properties.getCookieName(), "")
                .httpOnly(true)
                .secure(properties.isCookieSecure())
                .path(properties.getCookiePath())
                .sameSite(properties.getCookieSameSite())
                .maxAge(Duration.ZERO);
        if (!properties.getCookieDomain().isBlank()) {
            builder.domain(properties.getCookieDomain());
        }
        return builder.build();
    }

    /**
     * Reads the {@code stocka_refresh} cookie from the request.
     *
     * @param request HTTP request
     * @return the raw token, or empty when the cookie is absent or blank
     */
    public Optional<String> readFromRequest(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        String name = properties.getCookieName();
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                String value = cookie.getValue();
                if (value == null || value.isBlank()) {
                    return Optional.empty();
                }
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }
}
