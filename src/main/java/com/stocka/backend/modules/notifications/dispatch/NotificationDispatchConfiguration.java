package com.stocka.backend.modules.notifications.dispatch;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Activates {@link NotificationDispatchProperties} so it can be injected as a record.
 */
@Configuration
@EnableConfigurationProperties(NotificationDispatchProperties.class)
public class NotificationDispatchConfiguration {
}
