package com.stocka.backend.modules.devices.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DevicesProperties")
class DevicesPropertiesTest {

    @Test
    @DisplayName("should default to a 5 minute window and no base URL")
    void defaults() {
        DevicesProperties p = new DevicesProperties();
        assertThat(p.getOnlineWindow()).isEqualTo(Duration.ofMinutes(5));
        assertThat(p.getHeartbeatBaseUrl()).isEmpty();
    }

    @Test
    @DisplayName("should accept a positive window")
    void setOnlineWindow_should_acceptPositive() {
        DevicesProperties p = new DevicesProperties();
        p.setOnlineWindow(Duration.ofSeconds(90));
        assertThat(p.getOnlineWindow()).isEqualTo(Duration.ofSeconds(90));
    }

    @Test
    @DisplayName("should reject a null, zero or negative window")
    void setOnlineWindow_should_rejectNonPositive() {
        DevicesProperties p = new DevicesProperties();
        assertThatThrownBy(() -> p.setOnlineWindow(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> p.setOnlineWindow(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> p.setOnlineWindow(Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("should trim the base URL and map null to empty")
    void setHeartbeatBaseUrl_should_normalize() {
        DevicesProperties p = new DevicesProperties();
        p.setHeartbeatBaseUrl("  https://stocka.xeye.es ");
        assertThat(p.getHeartbeatBaseUrl()).isEqualTo("https://stocka.xeye.es");
        p.setHeartbeatBaseUrl(null);
        assertThat(p.getHeartbeatBaseUrl()).isEmpty();
    }
}
