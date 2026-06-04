package com.stocka.backend.modules.notifications.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.stocka.backend.modules.notifications.events.ResourceKind;
import com.stocka.backend.modules.notifications.events.ResourceLifecycleEvent;
import com.stocka.backend.modules.notifications.preferences.entity.LifecycleAction;

/**
 * Verifies that sync replay suppresses lifecycle notifications: the {@link
 * NotificationSuppressionContext} thread-local is captured onto the event at publish time, and the
 * dispatch listener drops events flagged as replay (B3).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Notification suppression on sync replay")
class NotificationSuppressionTest {

    @Mock
    private PendingResourceEventService pendingService;

    private ResourceLifecycleEvent newEvent() {
        return new ResourceLifecycleEvent(1, ResourceKind.PIECE, LifecycleAction.CREATED,
                10, "Widget", 2, 3);
    }

    @Test
    @DisplayName("context toggles suppression and restores the previous state (nesting)")
    void should_toggle_and_restore() {
        assertThat(NotificationSuppressionContext.isSuppressed()).isFalse();
        NotificationSuppressionContext.runSuppressed(() -> {
            assertThat(NotificationSuppressionContext.isSuppressed()).isTrue();
            NotificationSuppressionContext.runSuppressed(
                    () -> assertThat(NotificationSuppressionContext.isSuppressed()).isTrue());
            assertThat(NotificationSuppressionContext.isSuppressed()).isTrue();
        });
        assertThat(NotificationSuppressionContext.isSuppressed()).isFalse();
    }

    @Test
    @DisplayName("event captures the suppression flag at construction time")
    void should_capture_replay_flag_on_publish() {
        ResourceLifecycleEvent normal = newEvent();
        assertThat(normal.replay()).isFalse();

        ResourceLifecycleEvent replayed =
                NotificationSuppressionContext.runSuppressed(this::newEvent);
        assertThat(replayed.replay()).isTrue();
    }

    @Test
    @DisplayName("listener enqueues a normal event but skips a replayed one")
    void should_skip_replayed_events_in_listener() {
        ResourceLifecycleEventListener listener = new ResourceLifecycleEventListener(pendingService);

        ResourceLifecycleEvent normal = newEvent();
        listener.onResourceLifecycle(normal);
        verify(pendingService).enqueue(normal);

        ResourceLifecycleEvent replayed =
                NotificationSuppressionContext.runSuppressed(this::newEvent);
        listener.onResourceLifecycle(replayed);
        verify(pendingService, never()).enqueue(replayed);
    }
}
