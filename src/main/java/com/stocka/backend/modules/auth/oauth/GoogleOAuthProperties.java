package com.stocka.backend.modules.auth.oauth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.env.Environment;

import jakarta.annotation.PostConstruct;

/**
 * Configuration for the Google OAuth2 flow (Feature 4). Bound to the
 * {@code oauth.google} prefix in {@code application.properties}.
 *
 * <p>The flow is off unless {@link #isEnabled()} is {@code true}: the endpoints answer
 * {@code 404 auth.oauth_disabled} so the feature cannot be reached by accident. Enabling it
 * under a {@code prod} profile fails on startup ({@link #validateNotEnabledInProduction()}):
 * production only signs in with email, password and 2FA, the single path that keeps the
 * post-login redirect. When enabled but {@link #getClientId()} is blank the controller
 * short-circuits with {@code 503 auth.oauth_not_configured}.
 */
@ConfigurationProperties(prefix = "oauth.google")
public class GoogleOAuthProperties {

    private boolean enabled = false;
    private String clientId = "";
    private String clientSecret = "";
    private String redirectUri = "";
    private String authorizeEndpoint = "https://accounts.google.com/o/oauth2/v2/auth";
    private String tokenEndpoint = "https://oauth2.googleapis.com/token";
    private String userinfoEndpoint = "https://www.googleapis.com/oauth2/v2/userinfo";
    private int stateTtlSeconds = 600;

    @Autowired
    private Environment environment;

    /**
     * Refuses to boot with the Google flow enabled while an active Spring profile contains
     * {@code prod}.
     *
     * @throws IllegalStateException when enabled under a production profile
     */
    @PostConstruct
    public void validateNotEnabledInProduction() {
        if (enabled && hasProdProfile()) {
            throw new IllegalStateException(
                    "oauth.google.enabled=true is not allowed when an active Spring profile contains 'prod'. "
                            + "Set GOOGLE_OAUTH_ENABLED=false: production signs in with email + password only.");
        }
    }

    private boolean hasProdProfile() {
        if (environment == null) {
            return false;
        }
        for (String profile : environment.getActiveProfiles()) {
            if (profile != null && profile.toLowerCase().contains("prod")) {
                return true;
            }
        }
        return false;
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

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
