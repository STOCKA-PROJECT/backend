package com.stocka.backend.modules.storage;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.env.Environment;

/**
 * Cloudflare R2 (S3-compatible) configuration loaded from {@code stocka.r2.*} properties.
 *
 * <p>When {@link #useLocal} is {@code true} the application stores attachments under
 * {@link #localDir} on the local filesystem and serves them through {@code LocalR2DownloadController}.
 * Endpoint and credentials are ignored in that mode. The default is {@code false}: production
 * deployments must configure real Cloudflare R2 credentials.
 *
 * <p>{@link #validateProductionStorage()} runs on bean init and fails fast when
 * {@code useLocal=true} and any active Spring profile contains {@code prod}, to prevent
 * attachments from being silently written to ephemeral container storage in production.
 */
@ConfigurationProperties(prefix = "stocka.r2")
public class R2Properties {
    private boolean useLocal = false;
    private String bucket = "stocka-dev";
    private String endpoint = "";
    private String accessKey = "";
    private String secretKey = "";
    private int presignedTtlMinutes = 5;
    private String localDir = "/tmp/stocka-r2/";

    @Autowired
    private Environment environment;

    @PostConstruct
    void validateProductionStorage() {
        if (useLocal && hasProdProfile()) {
            throw new IllegalStateException(
                    "stocka.r2.use-local=true is not allowed when an active Spring profile contains 'prod'. "
                            + "Set R2_USE_LOCAL=false and provide R2_ENDPOINT, R2_ACCESS_KEY and R2_SECRET_KEY.");
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

    public boolean isUseLocal() {
        return useLocal;
    }

    public void setUseLocal(boolean useLocal) {
        this.useLocal = useLocal;
    }

    public String getBucket() {
        return bucket;
    }

    public void setBucket(String bucket) {
        this.bucket = bucket;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public int getPresignedTtlMinutes() {
        return presignedTtlMinutes;
    }

    public void setPresignedTtlMinutes(int presignedTtlMinutes) {
        this.presignedTtlMinutes = presignedTtlMinutes;
    }

    public String getLocalDir() {
        return localDir;
    }

    public void setLocalDir(String localDir) {
        this.localDir = localDir;
    }
}
