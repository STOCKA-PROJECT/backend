package com.stocka.backend.modules.pieces.service;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Activates {@link PieceAttachmentProperties}. */
@Configuration
@EnableConfigurationProperties(PieceAttachmentProperties.class)
public class PieceAttachmentConfig {
}
