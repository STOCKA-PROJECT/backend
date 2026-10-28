package com.stocka.backend.modules.devices.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the devices feature ({@code stocka.devices.*}).
 *
 * <p>{@link #onlineWindow} is how recent the last heartbeat must be for a device to be reported as
 * {@code online}. {@link #heartbeatBaseUrl} is the public base URL of this backend used to build the
 * {@code heartbeatUrl} shown next to a new token; when blank the URL is derived from the current
 * request (scheme, host and port after {@code X-Forwarded-*} processing). {@link #getLogs()} holds the
 * settings of the players' log kept in Stocka ({@code stocka.devices.logs.*}).
 */
@ConfigurationProperties(prefix = "stocka.devices")
public class DevicesProperties {
    /** Path of the public heartbeat endpoint, appended to the base URL. */
    public static final String HEARTBEAT_PATH = "/player/heartbeat";

    private Duration onlineWindow = Duration.ofMinutes(5);
    private String heartbeatBaseUrl = "";
    private final Logs logs = new Logs();

    public Duration getOnlineWindow() {
        return onlineWindow;
    }

    public void setOnlineWindow(Duration onlineWindow) {
        if (onlineWindow == null || onlineWindow.isZero() || onlineWindow.isNegative()) {
            throw new IllegalArgumentException("stocka.devices.online-window must be a positive duration");
        }
        this.onlineWindow = onlineWindow;
    }

    public String getHeartbeatBaseUrl() {
        return heartbeatBaseUrl;
    }

    public void setHeartbeatBaseUrl(String heartbeatBaseUrl) {
        this.heartbeatBaseUrl = heartbeatBaseUrl == null ? "" : heartbeatBaseUrl.trim();
    }

    public Logs getLogs() {
        return logs;
    }

    /**
     * The players' log kept in Stocka: how long it stays ({@link #retention}), the largest piece one
     * upload may carry ({@link #maxChunkBytes}), how many last lines a viewer may ask for
     * ({@link #maxTailLines}) and how many days one archive download may span ({@link #maxArchiveDays}).
     */
    public static class Logs {
        private Duration retention = Duration.ofDays(180);
        private int maxChunkBytes = 2 * 1024 * 1024;
        private int maxTailLines = 5000;
        private int maxArchiveDays = 62;

        public Duration getRetention() {
            return retention;
        }

        public void setRetention(Duration retention) {
            if (retention == null || retention.toDays() < 1) {
                throw new IllegalArgumentException("stocka.devices.logs.retention must be at least one day");
            }
            this.retention = retention;
        }

        public int getMaxChunkBytes() {
            return maxChunkBytes;
        }

        public void setMaxChunkBytes(int maxChunkBytes) {
            this.maxChunkBytes = positive("max-chunk-bytes", maxChunkBytes);
        }

        public int getMaxTailLines() {
            return maxTailLines;
        }

        public void setMaxTailLines(int maxTailLines) {
            this.maxTailLines = positive("max-tail-lines", maxTailLines);
        }

        public int getMaxArchiveDays() {
            return maxArchiveDays;
        }

        public void setMaxArchiveDays(int maxArchiveDays) {
            this.maxArchiveDays = positive("max-archive-days", maxArchiveDays);
        }

        private static int positive(String name, int value) {
            if (value <= 0) {
                throw new IllegalArgumentException("stocka.devices.logs." + name + " must be positive");
            }
            return value;
        }
    }
}
