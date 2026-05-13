package com.stocka.backend.modules.notifications.dispatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import com.stocka.backend.modules.notifications.events.ResourceLifecycleEvent;

/**
 * Bridges the synchronous {@link ResourceLifecycleEvent} published by the domain
 * services with the asynchronous coalescing layer. Two reasons for using
 * {@code TransactionalEventListener(AFTER_COMMIT)}:
 *
 * <ul>
 *   <li>a rolled-back create/update/delete must not produce a notification;</li>
 *   <li>the enqueue must run after the originating row is committed so a worker on
 *   another node cannot race to dispatch before it is visible.</li>
 * </ul>
 *
 * {@code @Async} keeps email-related I/O off the HTTP thread.
 */
@Component
public class ResourceLifecycleEventListener {
    private static final Logger log = LoggerFactory.getLogger(ResourceLifecycleEventListener.class);

    private final PendingResourceEventService pendingService;

    public ResourceLifecycleEventListener(PendingResourceEventService pendingService) {
        this.pendingService = pendingService;
    }

    @Async
    @TransactionalEventListener
    public void onResourceLifecycle(ResourceLifecycleEvent event) {
        try {
            pendingService.enqueue(event);
        } catch (RuntimeException ex) {
            log.warn("Failed to enqueue lifecycle event kind={} action={} resourceId={}: {}",
                    event.kind(), event.action(), event.resourceId(), ex.getMessage());
        }
    }
}
