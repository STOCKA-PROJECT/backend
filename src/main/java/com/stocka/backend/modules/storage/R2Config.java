package com.stocka.backend.modules.storage;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Activates {@link R2Properties} so the {@code stocka.r2.*} keys in {@code application.properties}
 * are bound and available to {@link R2Service} implementations.
 */
@Configuration
@EnableConfigurationProperties(R2Properties.class)
public class R2Config {
}
