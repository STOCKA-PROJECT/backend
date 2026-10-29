package com.stocka.backend.modules.notifications.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.stocka.backend.modules.notifications.dispatch.NotificationDigestDispatcher.Trigger;
import com.stocka.backend.modules.notifications.dispatch.repository.NotificationOutboxRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationDigestFlusher")
class NotificationDigestFlusherTest {

    @Mock private NotificationOutboxRepository outboxRepository;
    @Mock private NotificationDigestDispatcher dispatcher;
    @InjectMocks private NotificationDigestFlusher sut;

    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 29, 9, 0);

    @Nested
    @DisplayName("run")
    class Run {

        @Test
        @DisplayName("hands every pending group to the dispatcher with the trigger")
        void should_visitEveryGroup() {
            when(outboxRepository.findPendingGroups()).thenReturn(List.<Object[]>of(
                    new Object[]{1, 7, AT}, new Object[]{2, 7, AT.plusMinutes(1)}));
            when(dispatcher.processGroup(eq(1), eq(7), eq(AT), eq(Trigger.TICK), any())).thenReturn(true);
            when(dispatcher.processGroup(eq(2), eq(7), eq(AT.plusMinutes(1)), eq(Trigger.TICK), any())).thenReturn(false);

            assertThat(sut.run(Trigger.TICK)).isEqualTo(1);
        }

        @Test
        @DisplayName("a failing group does not stop the others")
        void should_continue_when_groupFails() {
            when(outboxRepository.findPendingGroups()).thenReturn(List.<Object[]>of(
                    new Object[]{1, 7, AT}, new Object[]{2, 7, AT}));
            when(dispatcher.processGroup(eq(1), eq(7), eq(AT), eq(Trigger.DAILY), any()))
                    .thenThrow(new IllegalStateException("boom"));
            when(dispatcher.processGroup(eq(2), eq(7), eq(AT), eq(Trigger.DAILY), any())).thenReturn(true);

            assertThat(sut.run(Trigger.DAILY)).isEqualTo(1);
        }

        @Test
        @DisplayName("does nothing without pending groups")
        void should_noop_when_nothingPending() {
            when(outboxRepository.findPendingGroups()).thenReturn(List.of());

            assertThat(sut.run(Trigger.TICK)).isZero();
        }
    }

    @Nested
    @DisplayName("scheduled entry points")
    class Scheduled {

        @Test
        @DisplayName("the tick runs with TICK")
        void should_runTick() {
            when(outboxRepository.findPendingGroups()).thenReturn(List.<Object[]>of(new Object[]{1, 7, AT}));

            sut.tick();

            verify(dispatcher).processGroup(eq(1), eq(7), eq(AT), eq(Trigger.TICK), any());
        }

        @Test
        @DisplayName("the daily run uses DAILY and purges sent rows")
        void should_runDaily_andPurge() {
            when(outboxRepository.findPendingGroups()).thenReturn(List.<Object[]>of(new Object[]{1, 7, AT}));
            when(dispatcher.purgeSent(any())).thenReturn(3);

            sut.daily();

            verify(dispatcher).processGroup(eq(1), eq(7), eq(AT), eq(Trigger.DAILY), any());
            verify(dispatcher).purgeSent(any());
        }

        @Test
        @DisplayName("a failing purge is logged and swallowed")
        void should_swallowPurgeFailure() {
            when(outboxRepository.findPendingGroups()).thenReturn(List.of());
            when(dispatcher.purgeSent(any())).thenThrow(new IllegalStateException("db"));

            sut.daily();
        }
    }
}
