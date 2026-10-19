package com.stocka.backend.modules.devices.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the devices feature ({@code stocka.devices.*}).
 *
 * <p>{@link #onlineWindow} is how recent the last heartbeat must be for a device to be reported as
 * {@code online}. {@link #heartbeatBaseUrl} is the public base URL of this backend used to build the
 * {@code heartbeatUrl} shown next to a new token; when blank the URL is derived from the current
 * request (scheme, host and port after {@code X-Forwarded-*} processing).
 */
@ConfigurationProperties(prefix = "stocka.devices")
public class DevicesProperties {
    /** Path of the public heartbeat endpoint, appended to the base URL. */
    public static final String HEARTBEAT_PATH = "/player/heartbeat";

    private Duration onlineWindow = Duration.ofMinutes(5);
    private String heartbeatBaseUrl = "";

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
}
