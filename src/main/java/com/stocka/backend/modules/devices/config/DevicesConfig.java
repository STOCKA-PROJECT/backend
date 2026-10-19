package com.stocka.backend.modules.devices.config;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Activates {@link DevicesProperties} and provides the {@link Clock} the online window is measured with. */
@Configuration
@EnableConfigurationProperties(DevicesProperties.class)
public class DevicesConfig {

    /**
     * System clock in the JVM default zone, matching the {@code LocalDateTime} timestamps Hibernate
     * writes. Tests replace it with a fixed clock.
     *
     * @return the clock used to stamp and evaluate heartbeats
     */
    @Bean
    public Clock devicesClock() {
        return Clock.systemDefaultZone();
    }
}
