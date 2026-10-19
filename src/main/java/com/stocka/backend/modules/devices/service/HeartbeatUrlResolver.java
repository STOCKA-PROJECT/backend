package com.stocka.backend.modules.devices.service;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.stocka.backend.modules.devices.config.DevicesProperties;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Builds the absolute URL a player must POST its heartbeat to, shown next to a freshly created
 * token so it can be pasted into the Pi's configuration.
 *
 * <p>When {@code stocka.devices.heartbeat-base-url} is set it wins (production pins it to the public
 * origin, by default the OAuth issuer). Otherwise the origin is taken from the current request:
 * {@code server.forward-headers-strategy=framework} already rewrote scheme, host and port from the
 * {@code X-Forwarded-*} headers of the reverse proxy, so behind Caddy this yields the https URL.
 */
@Component
public class HeartbeatUrlResolver {
    private final DevicesProperties properties;

    public HeartbeatUrlResolver(DevicesProperties properties) {
        this.properties = properties;
    }

    /**
     * Resolves the heartbeat URL for the given request.
     *
     * @param request current request (used only when no base URL is configured)
     * @return absolute URL ending in {@value DevicesProperties#HEARTBEAT_PATH}
     */
    public String resolve(HttpServletRequest request) {
        String base = properties.getHeartbeatBaseUrl();
        if (base != null && !base.isBlank()) {
            return stripTrailingSlash(base) + DevicesProperties.HEARTBEAT_PATH;
        }
        return ServletUriComponentsBuilder.fromContextPath(request)
                .path(DevicesProperties.HEARTBEAT_PATH)
                .build()
                .toUriString();
    }

    private static String stripTrailingSlash(String url) {
        String out = url;
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }
}
