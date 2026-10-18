package com.stocka.backend.modules.oauth;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the OAuth 2.1 authorization server that MCP connectors authenticate against. Bound to
 * {@code stocka.oauth.*}.
 *
 * <p>The server is embedded in the backend: the issuer is the backend's public URL and the only
 * protected resource is the MCP endpoint ({@link #resourceUrlOrDefault()}). Access tokens are JWTs
 * signed with the RSA key of {@link Jwk}; when no key is configured outside the {@code prod}
 * profile an ephemeral one is generated at startup (tokens then die with the process).
 */
@ConfigurationProperties(prefix = "stocka.oauth")
public class OAuthServerProperties {

    /** Default resource path appended to the issuer when {@code resource-url} is blank. */
    public static final String DEFAULT_RESOURCE_PATH = "/mcp";

    private boolean enabled = true;
    private String issuer = "http://localhost:9095";
    private String resourceUrl = "";
    private Duration accessTokenTtl = Duration.ofMinutes(30);
    private Duration refreshTokenTtl = Duration.ofDays(30);
    private Duration refreshAbsoluteTtl = Duration.ofDays(90);
    private Duration authorizationCodeTtl = Duration.ofMinutes(5);
    private final Jwk jwk = new Jwk();
    private final Dcr dcr = new Dcr();
    private final Cimd cimd = new Cimd();

    /** @return canonical URI of the protected resource (the MCP endpoint), without trailing slash */
    public String resourceUrlOrDefault() {
        String value = resourceUrl == null || resourceUrl.isBlank() ? issuer + DEFAULT_RESOURCE_PATH : resourceUrl;
        return stripTrailingSlash(value);
    }

    /** @return issuer without trailing slash */
    public String issuerOrDefault() {
        return stripTrailingSlash(issuer);
    }

    static String stripTrailingSlash(String value) {
        String v = value == null ? "" : value.trim();
        while (v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
        return v;
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getIssuer() { return issuer; }
    public void setIssuer(String issuer) { this.issuer = issuer == null ? "" : issuer.trim(); }
    public String getResourceUrl() { return resourceUrl; }
    public void setResourceUrl(String resourceUrl) { this.resourceUrl = resourceUrl == null ? "" : resourceUrl.trim(); }
    public Duration getAccessTokenTtl() { return accessTokenTtl; }
    public void setAccessTokenTtl(Duration accessTokenTtl) { this.accessTokenTtl = accessTokenTtl; }
    public Duration getRefreshTokenTtl() { return refreshTokenTtl; }
    public void setRefreshTokenTtl(Duration refreshTokenTtl) { this.refreshTokenTtl = refreshTokenTtl; }
    public Duration getRefreshAbsoluteTtl() { return refreshAbsoluteTtl; }
    public void setRefreshAbsoluteTtl(Duration refreshAbsoluteTtl) { this.refreshAbsoluteTtl = refreshAbsoluteTtl; }
    public Duration getAuthorizationCodeTtl() { return authorizationCodeTtl; }
    public void setAuthorizationCodeTtl(Duration authorizationCodeTtl) { this.authorizationCodeTtl = authorizationCodeTtl; }
    public Jwk getJwk() { return jwk; }
    public Dcr getDcr() { return dcr; }
    public Cimd getCimd() { return cimd; }

    /** Signing key material ({@code stocka.oauth.jwk.*}). PEM strings may carry literal {@code \n}. */
    public static class Jwk {
        private String privateKey = "";
        private String kid = "stocka-1";
        private String previousPrivateKey = "";
        private String previousKid = "stocka-0";

        public String getPrivateKey() { return privateKey; }
        public void setPrivateKey(String privateKey) { this.privateKey = privateKey == null ? "" : privateKey; }
        public String getKid() { return kid; }
        public void setKid(String kid) { this.kid = kid == null || kid.isBlank() ? "stocka-1" : kid.trim(); }
        public String getPreviousPrivateKey() { return previousPrivateKey; }
        public void setPreviousPrivateKey(String previousPrivateKey) {
            this.previousPrivateKey = previousPrivateKey == null ? "" : previousPrivateKey;
        }
        public String getPreviousKid() { return previousKid; }
        public void setPreviousKid(String previousKid) {
            this.previousKid = previousKid == null || previousKid.isBlank() ? "stocka-0" : previousKid.trim();
        }
        public boolean hasPrivateKey() { return !privateKey.isBlank(); }
        public boolean hasPreviousPrivateKey() { return !previousPrivateKey.isBlank(); }
    }

    /** Dynamic client registration (RFC 7591), {@code stocka.oauth.dcr.*}. */
    public static class Dcr {
        private boolean enabled = true;
        private Duration unusedClientTtl = Duration.ofDays(30);

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public Duration getUnusedClientTtl() { return unusedClientTtl; }
        public void setUnusedClientTtl(Duration unusedClientTtl) { this.unusedClientTtl = unusedClientTtl; }
    }

    /** Client ID Metadata Documents, {@code stocka.oauth.cimd.*}. */
    public static class Cimd {
        private List<String> trustedHosts = new ArrayList<>(List.of("claude.ai"));
        private Duration cacheTtl = Duration.ofHours(1);
        private Duration timeout = Duration.ofSeconds(3);
        private int maxBytes = 64 * 1024;

        public List<String> getTrustedHosts() { return trustedHosts; }
        public void setTrustedHosts(List<String> trustedHosts) {
            this.trustedHosts = trustedHosts == null ? new ArrayList<>() : new ArrayList<>(trustedHosts);
        }
        public Duration getCacheTtl() { return cacheTtl; }
        public void setCacheTtl(Duration cacheTtl) { this.cacheTtl = cacheTtl; }
        public Duration getTimeout() { return timeout; }
        public void setTimeout(Duration timeout) { this.timeout = timeout; }
        public int getMaxBytes() { return maxBytes; }
        public void setMaxBytes(int maxBytes) { this.maxBytes = maxBytes; }

        /** @return whether {@code host} (or a parent domain of it) is in the trusted list */
        public boolean isTrustedHost(String host) {
            if (host == null) return false;
            String h = host.toLowerCase();
            for (String trusted : trustedHosts) {
                String t = trusted == null ? "" : trusted.trim().toLowerCase();
                if (!t.isEmpty() && (h.equals(t) || h.endsWith("." + t))) return true;
            }
            return false;
        }
    }
}
