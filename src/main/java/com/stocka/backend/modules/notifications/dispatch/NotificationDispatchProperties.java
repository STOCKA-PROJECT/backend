package com.stocka.backend.modules.notifications.dispatch;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Runtime knobs for the coalescing notification dispatcher.
 *
 * @param windowMs         quiet-window after the last observed event before a row is
 *                         eligible for dispatch
 * @param flushIntervalMs  how often the flusher wakes up to pick up due rows
 * @param maxAttempts      retries before a row is force-deleted to avoid blocking the queue
 * @param batchSize        maximum number of rows processed per scheduler tick
 */
@ConfigurationProperties(prefix = "app.notifications")
public record NotificationDispatchProperties(
        long windowMs,
        long flushIntervalMs,
        int maxAttempts,
        int batchSize
) {
    public NotificationDispatchProperties {
        if (windowMs <= 0) windowMs = 60_000L;
        if (flushIntervalMs <= 0) flushIntervalMs = 15_000L;
        if (maxAttempts <= 0) maxAttempts = 5;
        if (batchSize <= 0) batchSize = 100;
    }
}
