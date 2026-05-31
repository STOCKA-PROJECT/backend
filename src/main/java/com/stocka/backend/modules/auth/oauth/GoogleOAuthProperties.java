package com.stocka.backend.modules.auth.oauth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the Google OAuth2 flow (Feature 4). Bound to the
 * {@code oauth.google} prefix in {@code application.properties}.
 *
 * <p>When {@link #getClientId()} is blank the controller short-circuits with
 * {@code 503 service.unavailable} — keeps the rest of the app booting cleanly
 * in environments where Google credentials aren't provisioned.
 */
@ConfigurationProperties(prefix = "oauth.google")
public class GoogleOAuthProperties {

    private String clientId = "";
    private String clientSecret = "";
    private String redirectUri = "";
    private String authorizeEndpoint = "https://accounts.google.com/o/oauth2/v2/auth";
    private String tokenEndpoint = "https://oauth2.googleapis.com/token";
    private String userinfoEndpoint = "https://www.googleapis.com/oauth2/v2/userinfo";
    private int stateTtlSeconds = 600;

    public boolean isConfigured() {
        return !clientId.isBlank() && !clientSecret.isBlank() && !redirectUri.isBlank();
    }

    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId == null ? "" : clientId; }

    public String getClientSecret() { return clientSecret; }
    public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret == null ? "" : clientSecret; }

    public String getRedirectUri() { return redirectUri; }
    public void setRedirectUri(String redirectUri) { this.redirectUri = redirectUri == null ? "" : redirectUri; }

    public String getAuthorizeEndpoint() { return authorizeEndpoint; }
    public void setAuthorizeEndpoint(String authorizeEndpoint) { this.authorizeEndpoint = authorizeEndpoint; }

    public String getTokenEndpoint() { return tokenEndpoint; }
    public void setTokenEndpoint(String tokenEndpoint) { this.tokenEndpoint = tokenEndpoint; }

    public String getUserinfoEndpoint() { return userinfoEndpoint; }
    public void setUserinfoEndpoint(String userinfoEndpoint) { this.userinfoEndpoint = userinfoEndpoint; }

    public int getStateTtlSeconds() { return stateTtlSeconds; }
    public void setStateTtlSeconds(int stateTtlSeconds) { this.stateTtlSeconds = stateTtlSeconds; }
}
