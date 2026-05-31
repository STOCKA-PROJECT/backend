package com.stocka.backend.modules.auth.oauth;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

import com.stocka.backend.modules.common.error.ApiException;
import com.stocka.backend.modules.common.error.ErrorCodes;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Glues the {@link GoogleOAuthClient} HTTP plumbing to the rest of the
 * application. Owns the state-cookie protocol that pairs an authorization
 * request with its callback.
 */
@Service
public class GoogleOAuthService {

    /** Cookie name carrying the per-flow state. Scoped to /auth. */
    public static final String STATE_COOKIE = "stocka_oauth_state";

    private final GoogleOAuthClient client;
    private final GoogleOAuthProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();

    public GoogleOAuthService(GoogleOAuthClient client, GoogleOAuthProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    public record AuthorizationResult(String authorizationUrl, ResponseCookie stateCookie) {}

    /**
     * Builds the Google authorize URL and the matching state cookie.
     *
     * @throws ApiException with {@code OAUTH_NOT_CONFIGURED} when credentials
     *                      aren't provisioned on this environment
     */
    public AuthorizationResult buildAuthorization() {
        ensureConfigured();
        String state = randomState();
        String url = properties.getAuthorizeEndpoint()
                + "?response_type=code"
                + "&client_id=" + URLEncoder.encode(properties.getClientId(), StandardCharsets.UTF_8)
                + "&redirect_uri=" + URLEncoder.encode(properties.getRedirectUri(), StandardCharsets.UTF_8)
                + "&scope=" + URLEncoder.encode("openid email profile", StandardCharsets.UTF_8)
                + "&state=" + URLEncoder.encode(state, StandardCharsets.UTF_8)
                + "&access_type=online"
                + "&prompt=select_account";

        ResponseCookie cookie = ResponseCookie.from(STATE_COOKIE, state)
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path("/auth")
                .maxAge(Duration.ofSeconds(properties.getStateTtlSeconds()))
                .build();
        return new AuthorizationResult(url, cookie);
    }

    /**
     * Verifies the state pair and exchanges the code for a user profile.
     *
     * @param presentedState state from the callback request body
     * @param request HTTP request — used to read the {@link #STATE_COOKIE}
     * @param code authorization code Google sent back
     * @return verified Google profile
     */
    public GoogleOAuthClient.UserInfo verifyAndFetchProfile(
            String presentedState, HttpServletRequest request, String code) {
        ensureConfigured();
        String cookieState = readStateCookie(request).orElse(null);
        if (cookieState == null || presentedState == null || !cookieState.equals(presentedState)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.OAUTH_STATE_INVALID);
        }
        GoogleOAuthClient.TokenResponse token = client.exchangeCodeForToken(code);
        return client.fetchUserInfo(token.accessToken());
    }

    public ResponseCookie buildClearingStateCookie() {
        return ResponseCookie.from(STATE_COOKIE, "")
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path("/auth")
                .maxAge(Duration.ZERO)
                .build();
    }

    private void ensureConfigured() {
        if (!properties.isConfigured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.OAUTH_NOT_CONFIGURED);
        }
    }

    private Optional<String> readStateCookie(HttpServletRequest request) {
        if (request.getCookies() == null) return Optional.empty();
        for (Cookie c : request.getCookies()) {
            if (STATE_COOKIE.equals(c.getName())) {
                String value = c.getValue();
                if (value == null || value.isBlank()) return Optional.empty();
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }

    private String randomState() {
        byte[] bytes = new byte[24];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
