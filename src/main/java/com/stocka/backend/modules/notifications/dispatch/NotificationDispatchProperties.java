package com.stocka.backend.modules.notifications.dispatch;

import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Runtime knobs for the coalescing notification dispatcher and the digest flusher.
 *
 * @param windowMs         quiet-window after the last observed event of a resource before its
 *                         row is eligible for dispatch
 * @param flushIntervalMs  how often the flushers wake up to pick up due rows
 * @param maxAttempts      retries before a row is force-deleted to avoid blocking the queue
 * @param batchSize        maximum number of rows processed per scheduler tick
 * @param batchWindowMs    for {@code BATCHED} recipients, how long an organization must stay
 *                         quiet before their pending events leave as one digest
 * @param digestCron       Spring cron expression of the {@code DAILY} digest
 * @param digestZone       time zone of {@code digestCron} and of the times printed in digests
 * @param retentionDays    days a sent outbox row is kept before the nightly purge removes it
 */
@ConfigurationProperties(prefix = "app.notifications")
public record NotificationDispatchProperties(
        long windowMs,
        long flushIntervalMs,
        int maxAttempts,
        int batchSize,
        long batchWindowMs,
        String digestCron,
        String digestZone,
        int retentionDays
) {
    public NotificationDispatchProperties {
        if (windowMs <= 0) windowMs = 60_000L;
        if (flushIntervalMs <= 0) flushIntervalMs = 15_000L;
        if (maxAttempts <= 0) maxAttempts = 5;
        if (batchSize <= 0) batchSize = 100;
        if (batchWindowMs <= 0) batchWindowMs = 1_800_000L;
        if (digestCron == null || digestCron.isBlank()) digestCron = "0 0 20 * * *";
        if (digestZone == null || digestZone.isBlank()) digestZone = "Europe/Madrid";
        if (retentionDays <= 0) retentionDays = 30;
    }

    /** The digest zone as a {@link ZoneId}, falling back to the system zone when invalid. */
    public ZoneId zoneId() {
        try {
            return ZoneId.of(digestZone);
        } catch (RuntimeException ex) {
            return ZoneId.systemDefault();
        }
    }
}
