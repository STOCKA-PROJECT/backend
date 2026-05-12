package com.stocka.backend.modules.organizations.service;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Activates {@link OrganizationQuotaProperties}. */
@Configuration
@EnableConfigurationProperties(OrganizationQuotaProperties.class)
public class OrganizationQuotaConfig {
}
