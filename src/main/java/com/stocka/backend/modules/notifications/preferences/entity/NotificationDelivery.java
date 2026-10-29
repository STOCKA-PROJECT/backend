package com.stocka.backend.modules.notifications.preferences.entity;

/**
 * How a user wants the lifecycle notifications of an organization delivered.
 *
 * <ul>
 *   <li>{@link #INSTANT}: one email per resource as soon as its quiet window elapses (the
 *   historical behaviour).</li>
 *   <li>{@link #BATCHED}: the events accumulate while the organization is active and a single
 *   digest leaves once no event has arrived for {@code app.notifications.batch-window-ms}.</li>
 *   <li>{@link #DAILY}: a single digest at the hour of {@code app.notifications.digest-cron}
 *   with everything that happened since the previous one.</li>
 * </ul>
 *
 * @since 2026-10-29
 */
public enum NotificationDelivery {
    INSTANT,
    BATCHED,
    DAILY
}
