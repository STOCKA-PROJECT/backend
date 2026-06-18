package com.stocka.backend.modules.pieces.importexport.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link PieceImportExportProperties} as a configuration-properties bean.
 */
@Configuration
@EnableConfigurationProperties(PieceImportExportProperties.class)
public class PieceImportExportConfig {
}
