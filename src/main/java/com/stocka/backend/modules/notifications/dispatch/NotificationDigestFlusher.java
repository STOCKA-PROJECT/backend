package com.stocka.backend.modules.notifications.dispatch;

import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.stocka.backend.modules.notifications.dispatch.NotificationDigestDispatcher.Trigger;
import com.stocka.backend.modules.notifications.dispatch.repository.NotificationOutboxRepository;

/**
 * Scheduled entry-points of the digest outbox: a tick every
 * {@code app.notifications.flush-interval-ms} (for {@code BATCHED} and {@code INSTANT} groups)
 * and a cron at {@code app.notifications.digest-cron} (for {@code DAILY} groups, which also
 * purges sent rows). The per-group work lives in {@link NotificationDigestDispatcher} so its
 * {@code REQUIRES_NEW} transaction goes through the proxy.
 *
 * @since 2026-10-29
 */
@Component
public class NotificationDigestFlusher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDigestFlusher.class);

    private final NotificationOutboxRepository outboxRepository;
    private final NotificationDigestDispatcher dispatcher;

    public NotificationDigestFlusher(NotificationOutboxRepository outboxRepository,
                                     NotificationDigestDispatcher dispatcher) {
        this.outboxRepository = outboxRepository;
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelayString = "${app.notifications.flush-interval-ms:15000}",
            initialDelayString = "${app.notifications.flush-interval-ms:15000}")
    public void tick() {
        run(Trigger.TICK);
    }

    @Scheduled(cron = "${app.notifications.digest-cron:0 0 20 * * *}",
            zone = "${app.notifications.digest-zone:Europe/Madrid}")
    public void daily() {
        run(Trigger.DAILY);
        try {
            int purged = dispatcher.purgeSent(LocalDateTime.now());
            if (purged > 0) {
                log.info("notification_outbox_purged rows={}", purged);
            }
        } catch (RuntimeException ex) {
            log.warn("Failed to purge sent outbox rows: {}", ex.getMessage());
        }
    }

    /**
     * Visits every pending group and lets the dispatcher decide. Package-private for tests.
     *
     * @param trigger what fired
     * @return groups that produced an email or were discarded
     */
    int run(Trigger trigger) {
        LocalDateTime now = LocalDateTime.now();
        List<Object[]> groups = outboxRepository.findPendingGroups();
        int done = 0;
        for (Object[] group : groups) {
            Integer userId = (Integer) group[0];
            Integer orgId = (Integer) group[1];
            LocalDateTime newest = (LocalDateTime) group[2];
            try {
                if (dispatcher.processGroup(userId, orgId, newest, trigger, now)) {
                    done++;
                }
            } catch (RuntimeException ex) {
                log.warn("Failed to flush digest for user={} org={}: {}", userId, orgId, ex.getMessage());
            }
        }
        return done;
    }
}
