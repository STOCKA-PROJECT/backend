package com.stocka.backend.modules.auth.oauth;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Talks to Google's OAuth2 endpoints over plain HTTP (no third-party client
 * library). Two calls per login:
 * <ol>
 *   <li>{@link #exchangeCodeForToken} on {@code /token} — code → access_token.</li>
 *   <li>{@link #fetchUserInfo} on {@code /userinfo} — access_token → profile.</li>
 * </ol>
 *
 * <p>Network failures are surfaced as {@link OAuthExchangeException}; the
 * controller maps them to {@code 502 gateway.upstream_unreachable}.
 */
@Component
public class GoogleOAuthClient {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper objectMapper;
    private final GoogleOAuthProperties properties;

    public GoogleOAuthClient(ObjectMapper objectMapper, GoogleOAuthProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /** Outcome of {@link #exchangeCodeForToken(String)}. */
    public record TokenResponse(String accessToken, String idToken) {}

    /**
     * Profile fields Google exposes via {@code /userinfo}. Only the subset we
     * actually use — Google returns a lot more.
     */
    public record UserInfo(String sub, String email, boolean emailVerified, String givenName, String familyName) {}

    public TokenResponse exchangeCodeForToken(String code) {
        String form = Map.of(
                "code", code,
                "client_id", properties.getClientId(),
                "client_secret", properties.getClientSecret(),
                "redirect_uri", properties.getRedirectUri(),
                "grant_type", "authorization_code"
        ).entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));

        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.getTokenEndpoint()))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();

        HttpResponse<String> response = send(request);
        if (response.statusCode() != 200) {
            throw new OAuthExchangeException("token endpoint returned " + response.statusCode());
        }
        try {
            JsonNode node = objectMapper.readTree(response.body());
            String accessToken = node.path("access_token").asText(null);
            String idToken = node.path("id_token").asText(null);
            if (accessToken == null) throw new OAuthExchangeException("access_token missing");
            return new TokenResponse(accessToken, idToken);
        } catch (RuntimeException e) {
            throw new OAuthExchangeException("malformed token response", e);
        }
    }

    public UserInfo fetchUserInfo(String accessToken) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.getUserinfoEndpoint()))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = send(request);
        if (response.statusCode() != 200) {
            throw new OAuthExchangeException("userinfo endpoint returned " + response.statusCode());
        }
        try {
            JsonNode node = objectMapper.readTree(response.body());
            String sub = node.path("id").asText(null);
            if (sub == null) sub = node.path("sub").asText(null);
            String email = node.path("email").asText(null);
            boolean emailVerified = node.path("verified_email").asBoolean(node.path("email_verified").asBoolean(false));
            String givenName = node.path("given_name").asText("");
            String familyName = node.path("family_name").asText("");
            if (sub == null || email == null) {
                throw new OAuthExchangeException("userinfo missing required fields");
            }
            return new UserInfo(sub, email, emailVerified, givenName, familyName);
        } catch (RuntimeException e) {
            throw new OAuthExchangeException("malformed userinfo response", e);
        }
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new OAuthExchangeException("network failure talking to Google", e);
        }
    }

    /**
     * Marker exception for upstream failures. Maps to
     * {@code 502 gateway.upstream_unreachable} in the global handler.
     */
    public static final class OAuthExchangeException extends RuntimeException {
        public OAuthExchangeException(String message) { super(message); }
        public OAuthExchangeException(String message, Throwable cause) { super(message, cause); }
    }
}
