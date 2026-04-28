package com.stocka.backend.modules.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cloudflare R2 (S3-compatible) configuration loaded from {@code stocka.r2.*} properties.
 *
 * <p>When {@link #useLocal} is {@code true} the application stores attachments under
 * {@link #localDir} on the local filesystem and serves them through {@code LocalR2DownloadController}.
 * Endpoint and credentials are ignored in that mode.
 */
@ConfigurationProperties(prefix = "stocka.r2")
public class R2Properties {
    private boolean useLocal = true;
    private String bucket = "stocka-dev";
    private String endpoint = "";
    private String accessKey = "";
    private String secretKey = "";
    private int presignedTtlMinutes = 5;
    private String localDir = "/tmp/stocka-r2/";

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
