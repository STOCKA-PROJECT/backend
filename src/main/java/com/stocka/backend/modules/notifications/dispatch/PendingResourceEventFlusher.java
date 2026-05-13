package com.stocka.backend.modules.notifications.dispatch;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.stocka.backend.modules.notifications.dispatch.repository.PendingResourceEventRepository;

/**
 * Drains the pending-event queue once the quiet window has elapsed. The per-row work lives in
 * {@link PendingResourceEventDispatcher}; this class is the {@code @Scheduled} entry-point and
 * only orchestrates the lookup + per-row delegation so {@code @Transactional(REQUIRES_NEW)}
 * on the dispatcher fires through the Spring AOP proxy.
 */
@Component
public class PendingResourceEventFlusher {
    private static final Logger log = LoggerFactory.getLogger(PendingResourceEventFlusher.class);

    private final PendingResourceEventRepository pendingRepository;
    private final PendingResourceEventDispatcher dispatcher;
    private final NotificationDispatchProperties properties;

    public PendingResourceEventFlusher(
            PendingResourceEventRepository pendingRepository,
            PendingResourceEventDispatcher dispatcher,
            NotificationDispatchProperties properties
    ) {
        this.pendingRepository = pendingRepository;
        this.dispatcher = dispatcher;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${app.notifications.flush-interval-ms:15000}")
    public void flushDue() {
        LocalDateTime threshold = LocalDateTime.now().minus(Duration.ofMillis(properties.windowMs()));
        List<Integer> dueIds = pendingRepository.findDueIds(threshold,
                PageRequest.of(0, Math.max(1, properties.batchSize())));
        for (Integer id : dueIds) {
            try {
                dispatcher.processOne(id);
            } catch (RuntimeException ex) {
                log.warn("Failed to flush pending resource event id={}: {}", id, ex.getMessage());
                try {
                    dispatcher.incrementOrDrop(id);
                } catch (RuntimeException retryEx) {
                    log.warn("Failed to update retry counter for pending event id={}: {}",
                            id, retryEx.getMessage());
                }
            }
        }
    }
}
