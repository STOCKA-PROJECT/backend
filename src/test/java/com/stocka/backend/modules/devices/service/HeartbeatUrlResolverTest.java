package com.stocka.backend.modules.devices.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import com.stocka.backend.modules.devices.config.DevicesProperties;

@DisplayName("HeartbeatUrlResolver")
class HeartbeatUrlResolverTest {

    private final DevicesProperties properties = new DevicesProperties();
    private final HeartbeatUrlResolver sut = new HeartbeatUrlResolver(properties);

    @Nested
    @DisplayName("with a configured base URL")
    class Configured {

        @Test
        @DisplayName("should append the heartbeat path to the configured origin")
        void resolve_should_useConfiguredBase() {
            properties.setHeartbeatBaseUrl("https://stocka.xeye.es");
            assertThat(sut.resolve(request("http", "localhost", 9095)))
                    .isEqualTo("https://stocka.xeye.es/player/heartbeat");
        }

        @Test
        @DisplayName("should strip trailing slashes before appending")
        void resolve_should_stripTrailingSlashes() {
            properties.setHeartbeatBaseUrl("https://stocka.xeye.es//");
            assertThat(sut.resolve(request("http", "localhost", 9095)))
                    .isEqualTo("https://stocka.xeye.es/player/heartbeat");
        }

        @Test
        @DisplayName("should keep a base path when the backend is mounted under one")
        void resolve_should_keepBasePath() {
            properties.setHeartbeatBaseUrl("https://example.org/api/");
            assertThat(sut.resolve(request("http", "localhost", 9095)))
                    .isEqualTo("https://example.org/api/player/heartbeat");
        }
    }

    @Nested
    @DisplayName("without a configured base URL")
    class Derived {

        @Test
        @DisplayName("should derive scheme, host and port from the request")
        void resolve_should_deriveFromRequest() {
            assertThat(sut.resolve(request("http", "localhost", 9095)))
                    .isEqualTo("http://localhost:9095/player/heartbeat");
        }

        @Test
        @DisplayName("should omit the default port")
        void resolve_should_omitDefaultPort() {
            assertThat(sut.resolve(request("https", "stocka.xeye.es", 443)))
                    .isEqualTo("https://stocka.xeye.es/player/heartbeat");
        }

        @Test
        @DisplayName("should include the servlet context path")
        void resolve_should_includeContextPath() {
            MockHttpServletRequest req = request("https", "example.org", 443);
            req.setContextPath("/api");
            assertThat(sut.resolve(req)).isEqualTo("https://example.org/api/player/heartbeat");
        }

        @Test
        @DisplayName("should treat a blank property as not configured")
        void resolve_should_treatBlankAsUnset() {
            properties.setHeartbeatBaseUrl("   ");
            assertThat(sut.resolve(request("http", "localhost", 9095)))
                    .isEqualTo("http://localhost:9095/player/heartbeat");
        }
    }

    private static MockHttpServletRequest request(String scheme, String host, int port) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/organizations/acme/devices");
        req.setScheme(scheme);
        req.setServerName(host);
        req.setServerPort(port);
        return req;
    }
}
