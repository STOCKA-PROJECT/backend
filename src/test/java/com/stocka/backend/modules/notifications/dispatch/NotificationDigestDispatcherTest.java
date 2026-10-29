package com.stocka.backend.modules.notifications.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.stocka.backend.modules.notifications.dispatch.NotificationDigestDispatcher.Trigger;
import com.stocka.backend.modules.notifications.dispatch.entity.NotificationOutboxEntry;
import com.stocka.backend.modules.notifications.dispatch.repository.NotificationOutboxRepository;
import com.stocka.backend.modules.notifications.email.ActivityDigestItem;
import com.stocka.backend.modules.notifications.email.EmailService;
import com.stocka.backend.modules.notifications.events.ResourceKind;
import com.stocka.backend.modules.notifications.preferences.entity.LifecycleAction;
import com.stocka.backend.modules.notifications.preferences.entity.NotificationDelivery;
import com.stocka.backend.modules.notifications.preferences.entity.NotificationPreference;
import com.stocka.backend.modules.notifications.preferences.service.NotificationPreferenceService;
import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.organizations.entity.OrganizationMember;
import com.stocka.backend.modules.organizations.repository.OrganizationMemberRepository;
import com.stocka.backend.modules.organizations.repository.OrganizationRepository;
import com.stocka.backend.modules.users.entity.Language;
import com.stocka.backend.modules.users.entity.User;
import com.stocka.backend.modules.users.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationDigestDispatcher")
class NotificationDigestDispatcherTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 29, 12, 0);
    private static final long WINDOW_MS = 30 * 60_000L;

    @Mock private NotificationOutboxRepository outboxRepository;
    @Mock private UserRepository userRepository;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private OrganizationMemberRepository memberRepository;
    @Mock private NotificationPreferenceService preferenceService;
    @Mock private EmailService emailService;

    private NotificationDigestDispatcher sut;
    private NotificationDispatchProperties properties;
    private User recipient;
    private Organization org;
    private NotificationOutboxEntry first;
    private NotificationOutboxEntry second;

    @BeforeEach
    void setUp() {
        properties = new NotificationDispatchProperties(0, 0, 0, 0, WINDOW_MS, null, "UTC", 30);
        sut = new NotificationDigestDispatcher(outboxRepository, userRepository, organizationRepository,
                memberRepository, preferenceService, emailService, properties, "https://app.test");
        recipient = new User().setId(2).setEmail("joan@test").setEmailVerified(true).setLanguage(Language.CA);
        org = new Organization().setId(7).setName("Betlem").setSlug("betlem");
        first = entry(10, LifecycleAction.CREATED, NOW.minusHours(2));
        second = entry(11, LifecycleAction.EDITED, NOW.minusHours(1));
        lenient().when(outboxRepository.findByUserIdAndOrganizationIdAndSentAtIsNullOrderByEventAtAscIdAsc(2, 7))
                .thenReturn(List.of(first, second));
        lenient().when(userRepository.findById(2)).thenReturn(Optional.of(recipient));
        lenient().when(organizationRepository.findById(7)).thenReturn(Optional.of(org));
        lenient().when(memberRepository.findByUserAndOrganization(recipient, org))
                .thenReturn(Optional.of(new OrganizationMember().setUser(recipient).setOrganization(org)));
    }

    private NotificationOutboxEntry entry(int id, LifecycleAction action, LocalDateTime at) {
        return new NotificationOutboxEntry().setId(id).setUser(recipient).setOrganization(org)
                .setResourceKind(ResourceKind.PIECE).setResourceId(id * 10).setAction(action)
                .setResourceName("Peça " + id).setActorName("Anna")
                .setResourceUrl("https://app.test/dashboard/betlem/articulos/" + id * 10).setEventAt(at);
    }

    private void prefWith(NotificationDelivery delivery) {
        when(preferenceService.resolveOrDefault(recipient, org))
                .thenReturn(new NotificationPreference().setUser(recipient).setOrganization(org).setDelivery(delivery));
    }

    @Nested
    @DisplayName("isDue")
    class IsDue {

        @Test
        @DisplayName("INSTANT leaves on a tick, never on the daily run")
        void should_handleInstant() {
            assertThat(sut.isDue(NotificationDelivery.INSTANT, Trigger.TICK, NOW, NOW)).isTrue();
            assertThat(sut.isDue(NotificationDelivery.INSTANT, Trigger.DAILY, NOW, NOW)).isFalse();
        }

        @Test
        @DisplayName("BATCHED leaves on a tick once the newest event is older than the window")
        void should_handleBatched() {
            assertThat(sut.isDue(NotificationDelivery.BATCHED, Trigger.TICK, NOW.minusMinutes(31), NOW)).isTrue();
            assertThat(sut.isDue(NotificationDelivery.BATCHED, Trigger.TICK, NOW.minusMinutes(30), NOW)).isTrue();
            assertThat(sut.isDue(NotificationDelivery.BATCHED, Trigger.TICK, NOW.minusMinutes(29), NOW)).isFalse();
            assertThat(sut.isDue(NotificationDelivery.BATCHED, Trigger.TICK, null, NOW)).isFalse();
            assertThat(sut.isDue(NotificationDelivery.BATCHED, Trigger.DAILY, NOW.minusHours(5), NOW)).isFalse();
        }

        @Test
        @DisplayName("DAILY leaves only on the daily run")
        void should_handleDaily() {
            assertThat(sut.isDue(NotificationDelivery.DAILY, Trigger.DAILY, NOW.minusSeconds(1), NOW)).isTrue();
            assertThat(sut.isDue(NotificationDelivery.DAILY, Trigger.TICK, NOW.minusDays(1), NOW)).isFalse();
        }
    }

    @Nested
    @DisplayName("processGroup — sending")
    class Sending {

        @Test
        @DisplayName("sends one digest with every pending event and marks them sent")
        void should_sendDigest_andMarkSent() {
            prefWith(NotificationDelivery.BATCHED);

            boolean done = sut.processGroup(2, 7, NOW.minusHours(1), Trigger.TICK, NOW);

            assertThat(done).isTrue();
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<ActivityDigestItem>> items = ArgumentCaptor.forClass(List.class);
            verify(emailService).sendActivityDigestEmail(eq("joan@test"), eq("Betlem"),
                    eq("https://app.test/dashboard/betlem"), items.capture(), eq("7/10-11/2"), eq(Language.CA));
            assertThat(items.getValue()).hasSize(2);
            assertThat(items.getValue().get(0).resourceName()).isEqualTo("Peça 10");
            assertThat(items.getValue().get(0).action()).isEqualTo(LifecycleAction.CREATED);
            assertThat(items.getValue().get(1).actorName()).isEqualTo("Anna");
            assertThat(first.getSentAt()).isEqualTo(NOW);
            assertThat(second.getSentAt()).isEqualTo(NOW);
            verify(outboxRepository).saveAll(List.of(first, second));
        }

        @Test
        @DisplayName("prints the event times in the digest zone")
        void should_convertTimes_toDigestZone() {
            properties = new NotificationDispatchProperties(0, 0, 0, 0, WINDOW_MS, null, "Europe/Madrid", 30);
            sut = new NotificationDigestDispatcher(outboxRepository, userRepository, organizationRepository,
                    memberRepository, preferenceService, emailService, properties, "https://app.test");
            prefWith(NotificationDelivery.BATCHED);

            sut.processGroup(2, 7, NOW.minusHours(1), Trigger.TICK, NOW);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<ActivityDigestItem>> items = ArgumentCaptor.forClass(List.class);
            verify(emailService).sendActivityDigestEmail(anyString(), anyString(), anyString(), items.capture(),
                    anyString(), any());
            LocalDateTime expected = first.getEventAt().atZone(ZoneId.systemDefault())
                    .withZoneSameInstant(ZoneId.of("Europe/Madrid")).toLocalDateTime();
            assertThat(items.getValue().get(0).eventAt()).isEqualTo(expected);
        }

        @Test
        @DisplayName("falls back to Spanish when the recipient has no language")
        void should_fallbackToSpanish_when_noLanguage() {
            recipient.setLanguage(null);
            prefWith(NotificationDelivery.INSTANT);

            sut.processGroup(2, 7, NOW, Trigger.TICK, NOW);

            verify(emailService).sendActivityDigestEmail(anyString(), anyString(), anyString(), anyList(),
                    anyString(), eq(Language.ES));
        }

        @Test
        @DisplayName("uses the default mode when the preference has no delivery")
        void should_useBatched_when_deliveryNull() {
            when(preferenceService.resolveOrDefault(recipient, org)).thenReturn(null);

            assertThat(sut.processGroup(2, 7, NOW.minusMinutes(5), Trigger.TICK, NOW)).isFalse();
            assertThat(sut.processGroup(2, 7, NOW.minusMinutes(45), Trigger.TICK, NOW)).isTrue();
        }

        @Test
        @DisplayName("an email failure leaves the rows pending for the next trigger")
        void should_keepPending_when_emailThrows() {
            prefWith(NotificationDelivery.DAILY);
            doThrow(new IllegalStateException("boom")).when(emailService).sendActivityDigestEmail(
                    anyString(), anyString(), anyString(), anyList(), anyString(), any());

            assertThatThrownBy(() -> sut.processGroup(2, 7, NOW, Trigger.DAILY, NOW))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(first.getSentAt()).isNull();
            verify(outboxRepository, never()).saveAll(anyList());
        }
    }

    @Nested
    @DisplayName("processGroup — waiting and discarding")
    class WaitingAndDiscarding {

        @Test
        @DisplayName("returns true without email when the group is already empty")
        void should_noop_when_empty() {
            when(outboxRepository.findByUserIdAndOrganizationIdAndSentAtIsNullOrderByEventAtAscIdAsc(2, 7))
                    .thenReturn(List.of());

            assertThat(sut.processGroup(2, 7, NOW, Trigger.TICK, NOW)).isTrue();
            verifyNoInteractions(emailService);
        }

        @Test
        @DisplayName("keeps a BATCHED group that is still inside the window")
        void should_wait_when_batchedNotDue() {
            prefWith(NotificationDelivery.BATCHED);

            assertThat(sut.processGroup(2, 7, NOW.minusMinutes(5), Trigger.TICK, NOW)).isFalse();
            verifyNoInteractions(emailService);
            assertThat(first.getSentAt()).isNull();
        }

        @Test
        @DisplayName("keeps a DAILY group on a tick")
        void should_wait_when_dailyOnTick() {
            prefWith(NotificationDelivery.DAILY);

            assertThat(sut.processGroup(2, 7, NOW.minusDays(1), Trigger.TICK, NOW)).isFalse();
            verifyNoInteractions(emailService);
        }

        @Test
        @DisplayName("discards the rows when the recipient no longer exists")
        void should_discard_when_userGone() {
            when(userRepository.findById(2)).thenReturn(Optional.empty());

            assertThat(sut.processGroup(2, 7, NOW, Trigger.TICK, NOW)).isTrue();
            verify(outboxRepository).deleteAll(List.of(first, second));
            verifyNoInteractions(emailService);
        }

        @Test
        @DisplayName("discards the rows when the organization was deleted")
        void should_discard_when_orgDeleted() {
            org.setDeletedAt(NOW);

            assertThat(sut.processGroup(2, 7, NOW, Trigger.TICK, NOW)).isTrue();
            verify(outboxRepository).deleteAll(List.of(first, second));
            verifyNoInteractions(emailService);
        }

        @Test
        @DisplayName("discards the rows when the recipient left the organization")
        void should_discard_when_notMember() {
            when(memberRepository.findByUserAndOrganization(recipient, org)).thenReturn(Optional.empty());

            assertThat(sut.processGroup(2, 7, NOW, Trigger.TICK, NOW)).isTrue();
            verify(outboxRepository).deleteAll(List.of(first, second));
            verifyNoInteractions(emailService);
        }

        @Test
        @DisplayName("discards the rows when the email is no longer verified")
        void should_discard_when_emailUnverified() {
            recipient.setEmailVerified(false);

            assertThat(sut.processGroup(2, 7, NOW, Trigger.TICK, NOW)).isTrue();
            verify(outboxRepository).deleteAll(List.of(first, second));
            verifyNoInteractions(emailService);
        }
    }

    @Nested
    @DisplayName("purgeSent")
    class PurgeSent {

        @Test
        @DisplayName("deletes rows sent before the retention period")
        void should_deleteOldSentRows() {
            when(outboxRepository.deleteSentBefore(NOW.minusDays(30))).thenReturn(4);

            assertThat(sut.purgeSent(NOW)).isEqualTo(4);
        }
    }
}
