package com.stocka.backend.modules.search;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers {@link SemanticSearchProperties}. */
@Configuration
@EnableConfigurationProperties(SemanticSearchProperties.class)
public class SemanticSearchConfiguration {
}
