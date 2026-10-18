package com.stocka.backend.modules.search.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.stocka.backend.modules.search.SemanticSearchProperties;
import com.stocka.backend.modules.search.client.XeyeClient;
import com.stocka.backend.modules.search.client.XeyeClient.Training;
import com.stocka.backend.modules.search.client.XeyeException;
import com.stocka.backend.modules.search.entity.SearchList;
import com.stocka.backend.modules.search.repository.SearchListRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("SemanticSearchTrainer")
class SemanticSearchTrainerTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 18, 12, 0);

    @Mock private XeyeClient client;
    @Mock private SearchListRepository lists;

    private final SemanticSearchProperties properties = new SemanticSearchProperties();
    private SemanticSearchTrainer sut;
    private SearchList list;

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        properties.setApiKey("k");
        properties.setTrainingDelay(Duration.ofMinutes(10));
        sut = new SemanticSearchTrainer(properties, client, lists,
                Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
        list = new SearchList().setOrganizationId(3).setXeyeListId(9L).setListName("stocka-org-3")
                .setPendingSince(NOW.minusMinutes(30));
        lenient().when(lists.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Nested
    @DisplayName("schedule")
    class Schedule {

        @Test
        @DisplayName("does nothing when the feature is not configured")
        void notConfigured() {
            properties.setEnabled(false);
            sut.launchDueTrainings();
            verifyNoInteractions(client, lists);
        }

        @Test
        @DisplayName("only considers lists pending for longer than the training delay")
        void cutoff() {
            when(lists.findByPendingSinceBefore(NOW.minusMinutes(10))).thenReturn(List.of());
            sut.launchDueTrainings();
            verify(lists).findByPendingSinceBefore(NOW.minusMinutes(10));
            verifyNoInteractions(client);
        }

        @Test
        @DisplayName("launches every due list")
        void launchesDue() {
            SearchList other = new SearchList().setOrganizationId(4).setXeyeListId(10L).setPendingSince(NOW.minusHours(1));
            when(lists.findByPendingSinceBefore(any())).thenReturn(List.of(list, other));
            when(client.launchTraining(9L)).thenReturn(new Training(70, "QUEUED"));
            when(client.launchTraining(10L)).thenReturn(new Training(71, "QUEUED"));

            sut.launchDueTrainings();

            assertThat(list.getLastTrainingId()).isEqualTo(70L);
            assertThat(other.getLastTrainingId()).isEqualTo(71L);
        }
    }

    @Nested
    @DisplayName("launch")
    class Launch {

        @Test
        @DisplayName("clears the pending flag and records the training on success")
        void success() {
            when(client.launchTraining(9L)).thenReturn(new Training(70, "QUEUED"));

            sut.launch(list);

            assertThat(list.getPendingSince()).isNull();
            assertThat(list.getLastTrainingLaunchedAt()).isEqualTo(NOW);
            assertThat(list.getLastTrainingId()).isEqualTo(70L);
            verify(lists).save(list);
        }

        @Test
        @DisplayName("keeps the list pending while a training is already running")
        void conflict() {
            when(client.launchTraining(9L)).thenThrow(new XeyeException(409, "CONFLICT", "running"));

            sut.launch(list);

            assertThat(list.getPendingSince()).isEqualTo(NOW.minusMinutes(30));
            verify(lists, never()).save(any());
        }

        @Test
        @DisplayName("keeps the list pending on a temporary failure")
        void retryable() {
            when(client.launchTraining(9L)).thenThrow(new XeyeException(503, "SERVICE_UNAVAILABLE", "down"));

            sut.launch(list);

            assertThat(list.getPendingSince()).isNotNull();
            verify(lists, never()).save(any());
        }

        @Test
        @DisplayName("keeps the list pending when the account has no credit")
        void insufficientCredit() {
            when(client.launchTraining(9L)).thenThrow(new XeyeException(402, "INSUFFICIENT_CREDIT", "top up"));

            sut.launch(list);

            assertThat(list.getPendingSince()).isNotNull();
            verify(lists, never()).save(any());
        }

        @Test
        @DisplayName("clears the pending flag when XEYE rejects the launch for good")
        void rejected() {
            when(client.launchTraining(9L)).thenThrow(new XeyeException(400, "VALIDATION_FAILED", "empty list"));

            sut.launch(list);

            assertThat(list.getPendingSince()).isNull();
            assertThat(list.getLastTrainingId()).isNull();
            verify(lists).save(list);
        }
    }
}
