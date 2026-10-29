package com.stocka.backend.modules.notifications.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;

import com.stocka.backend.modules.notifications.dispatch.entity.NotificationOutboxEntry;
import com.stocka.backend.modules.notifications.dispatch.entity.PendingResourceEvent;
import com.stocka.backend.modules.notifications.dispatch.repository.NotificationOutboxRepository;
import com.stocka.backend.modules.notifications.dispatch.repository.PendingResourceEventRepository;
import com.stocka.backend.modules.notifications.email.EmailService;
import com.stocka.backend.modules.notifications.events.ResourceKind;
import com.stocka.backend.modules.notifications.preferences.entity.LifecycleAction;
import com.stocka.backend.modules.notifications.preferences.entity.NotificationDelivery;
import com.stocka.backend.modules.notifications.preferences.entity.NotificationPreference;
import com.stocka.backend.modules.notifications.preferences.entity.PieceScope;
import com.stocka.backend.modules.notifications.preferences.service.NotificationPreferenceService;
import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.organizations.entity.OrganizationMember;
import com.stocka.backend.modules.organizations.repository.OrganizationMemberRepository;
import com.stocka.backend.modules.users.entity.Language;
import com.stocka.backend.modules.users.entity.User;
import com.stocka.backend.modules.users.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("PendingResourceEventDispatcher")
class PendingResourceEventDispatcherTest {

    @Mock private PendingResourceEventRepository pendingRepository;
    @Mock private NotificationOutboxRepository outboxRepository;
    @Mock private OrganizationMemberRepository memberRepository;
    @Mock private UserRepository userRepository;
    @Mock private NotificationPreferenceService preferenceService;
    @Mock private EmailService emailService;
    @Mock private MessageSource messageSource;

    private PendingResourceEventDispatcher sut;
    private Organization org;
    private User actor;
    private User recipient;
    private PendingResourceEvent row;

    @BeforeEach
    void setUp() {
        sut = new PendingResourceEventDispatcher(pendingRepository, outboxRepository, memberRepository,
                userRepository, preferenceService, emailService,
                new NotificationDispatchProperties(0, 0, 0, 0, 0, null, null, 0),
                messageSource, "https://app.test/");
        org = new Organization().setId(7).setName("Betlem").setSlug("betlem");
        actor = new User().setId(1).setName("Anna").setLastName("Puig").setEmail("anna@test").setEmailVerified(true);
        recipient = new User().setId(2).setName("Joan").setEmail("joan@test").setEmailVerified(true)
                .setLanguage(Language.CA);
        row = new PendingResourceEvent().setId(99).setOrganization(org).setResourceKind(ResourceKind.PIECE)
                .setResourceId(55).setFirstAction(LifecycleAction.CREATED).setLastAction(LifecycleAction.CREATED)
                .setFirstEventAt(LocalDateTime.of(2026, 10, 29, 9, 0))
                .setLastEventAt(LocalDateTime.of(2026, 10, 29, 9, 5))
                .setActorUserId(1).setOwnerUserId(2).setResourceName("Pastor");
        lenient().when(pendingRepository.findById(99)).thenReturn(Optional.of(row));
        lenient().when(userRepository.findById(1)).thenReturn(Optional.of(actor));
        lenient().when(memberRepository.findByOrganization(org)).thenReturn(List.of(
                member(actor), member(recipient)));
    }

    private OrganizationMember member(User user) {
        return new OrganizationMember().setUser(user).setOrganization(org);
    }

    private NotificationPreference pref(NotificationDelivery delivery, LifecycleAction... pieces) {
        return new NotificationPreference().setUser(recipient).setOrganization(org)
                .setPieces(pieces.length == 0 ? EnumSet.noneOf(LifecycleAction.class) : EnumSet.of(pieces[0], pieces))
                .setPieceScope(PieceScope.ALL).setDelivery(delivery);
    }

    @Nested
    @DisplayName("reduce")
    class Reduce {

        @ParameterizedTest(name = "{0} then {1} → {2}")
        @CsvSource({
                "CREATED,CREATED,CREATED",
                "CREATED,EDITED,CREATED",
                "EDITED,EDITED,EDITED",
                "EDITED,DELETED,DELETED",
                "DELETED,DELETED,DELETED",
                "DELETED,EDITED,DELETED",
        })
        void should_reducePairs(LifecycleAction first, LifecycleAction last, LifecycleAction expected) {
            assertThat(PendingResourceEventDispatcher.reduce(first, last)).isEqualTo(expected);
        }

        @Test
        @DisplayName("created and deleted inside the window is transient")
        void should_returnNull_when_createdThenDeleted() {
            assertThat(PendingResourceEventDispatcher.reduce(LifecycleAction.CREATED, LifecycleAction.DELETED)).isNull();
        }

        @Test
        @DisplayName("null inputs reduce to null")
        void should_returnNull_when_nullInputs() {
            assertThat(PendingResourceEventDispatcher.reduce(null, LifecycleAction.CREATED)).isNull();
            assertThat(PendingResourceEventDispatcher.reduce(LifecycleAction.CREATED, null)).isNull();
        }
    }

    @Nested
    @DisplayName("processOne — row handling")
    class RowHandling {

        @Test
        @DisplayName("does nothing when the row vanished")
        void should_ignore_when_rowMissing() {
            when(pendingRepository.findById(5)).thenReturn(Optional.empty());

            sut.processOne(5);

            verifyNoInteractions(emailService, outboxRepository);
        }

        @Test
        @DisplayName("deletes a transient row without notifying")
        void should_delete_when_transient() {
            row.setLastAction(LifecycleAction.DELETED);

            sut.processOne(99);

            verify(pendingRepository).delete(row);
            verifyNoInteractions(emailService, outboxRepository, memberRepository);
        }

        @Test
        @DisplayName("deletes the row of a deleted organization without notifying")
        void should_delete_when_orgDeleted() {
            org.setDeletedAt(LocalDateTime.now());

            sut.processOne(99);

            verify(pendingRepository).delete(row);
            verifyNoInteractions(emailService, outboxRepository, memberRepository);
        }

        @Test
        @DisplayName("deletes the row after the fan-out")
        void should_deleteRow_afterFanOut() {
            when(preferenceService.resolveOrDefault(recipient, org))
                    .thenReturn(pref(NotificationDelivery.INSTANT, LifecycleAction.CREATED));

            sut.processOne(99);

            verify(pendingRepository).delete(row);
        }
    }

    @Nested
    @DisplayName("processOne — recipient filters")
    class RecipientFilters {

        @Test
        @DisplayName("never notifies the actor")
        void should_skipActor() {
            when(preferenceService.resolveOrDefault(recipient, org))
                    .thenReturn(pref(NotificationDelivery.INSTANT, LifecycleAction.CREATED));

            sut.processOne(99);

            verify(preferenceService, never()).resolveOrDefault(actor, org);
            verify(emailService).sendResourceLifecycleEmail(eq("joan@test"), any(), any(), anyString(),
                    anyString(), anyString(), anyString(), any());
        }

        @Test
        @DisplayName("skips members without a verified email")
        void should_skip_when_emailUnverified() {
            recipient.setEmailVerified(false);

            sut.processOne(99);

            verifyNoInteractions(emailService, outboxRepository, preferenceService);
        }

        @Test
        @DisplayName("skips members not subscribed to the effective action")
        void should_skip_when_actionNotSubscribed() {
            when(preferenceService.resolveOrDefault(recipient, org))
                    .thenReturn(pref(NotificationDelivery.INSTANT, LifecycleAction.EDITED));

            sut.processOne(99);

            verifyNoInteractions(emailService, outboxRepository);
        }

        @Test
        @DisplayName("skips pieces of others when the scope is OWNED_ONLY")
        void should_skip_when_ownedOnlyAndNotOwner() {
            row.setOwnerUserId(1);
            when(preferenceService.resolveOrDefault(recipient, org))
                    .thenReturn(pref(NotificationDelivery.INSTANT, LifecycleAction.CREATED)
                            .setPieceScope(PieceScope.OWNED_ONLY));

            sut.processOne(99);

            verifyNoInteractions(emailService, outboxRepository);
        }

        @Test
        @DisplayName("keeps pieces the recipient owns when the scope is OWNED_ONLY")
        void should_notify_when_ownedOnlyAndOwner() {
            when(preferenceService.resolveOrDefault(recipient, org))
                    .thenReturn(pref(NotificationDelivery.INSTANT, LifecycleAction.CREATED)
                            .setPieceScope(PieceScope.OWNED_ONLY));

            sut.processOne(99);

            verify(emailService).sendResourceLifecycleEmail(eq("joan@test"), eq(ResourceKind.PIECE),
                    eq(LifecycleAction.CREATED), eq("Pastor"), eq("Betlem"), eq("Anna Puig"),
                    eq("https://app.test/dashboard/betlem/articulos/55"), eq(Language.CA));
        }

        @Test
        @DisplayName("ignores the piece scope for locations")
        void should_ignoreScope_forLocations() {
            row.setResourceKind(ResourceKind.LOCATION).setOwnerUserId(null);
            when(preferenceService.resolveOrDefault(recipient, org))
                    .thenReturn(pref(NotificationDelivery.INSTANT).setPieceScope(PieceScope.OWNED_ONLY)
                            .setLocations(EnumSet.of(LifecycleAction.CREATED)));

            sut.processOne(99);

            verify(emailService).sendResourceLifecycleEmail(eq("joan@test"), eq(ResourceKind.LOCATION),
                    eq(LifecycleAction.CREATED), anyString(), anyString(), anyString(),
                    eq("https://app.test/dashboard/betlem/ubicaciones"), any());
        }
    }

    @Nested
    @DisplayName("processOne — delivery modes")
    class DeliveryModes {

        @Test
        @DisplayName("INSTANT sends one email and never touches the outbox")
        void should_sendEmail_when_instant() {
            when(preferenceService.resolveOrDefault(recipient, org))
                    .thenReturn(pref(NotificationDelivery.INSTANT, LifecycleAction.CREATED));

            sut.processOne(99);

            verify(emailService).sendResourceLifecycleEmail(eq("joan@test"), any(), any(), anyString(),
                    anyString(), anyString(), anyString(), any());
            verifyNoInteractions(outboxRepository);
        }

        @Test
        @DisplayName("BATCHED parks the event in the outbox with its snapshots")
        void should_enqueueOutbox_when_batched() {
            when(preferenceService.resolveOrDefault(recipient, org))
                    .thenReturn(pref(NotificationDelivery.BATCHED, LifecycleAction.CREATED));

            sut.processOne(99);

            ArgumentCaptor<NotificationOutboxEntry> captor = ArgumentCaptor.forClass(NotificationOutboxEntry.class);
            verify(outboxRepository).save(captor.capture());
            NotificationOutboxEntry entry = captor.getValue();
            assertThat(entry.getUser()).isSameAs(recipient);
            assertThat(entry.getOrganization()).isSameAs(org);
            assertThat(entry.getResourceKind()).isEqualTo(ResourceKind.PIECE);
            assertThat(entry.getResourceId()).isEqualTo(55);
            assertThat(entry.getAction()).isEqualTo(LifecycleAction.CREATED);
            assertThat(entry.getResourceName()).isEqualTo("Pastor");
            assertThat(entry.getActorName()).isEqualTo("Anna Puig");
            assertThat(entry.getResourceUrl()).isEqualTo("https://app.test/dashboard/betlem/articulos/55");
            assertThat(entry.getEventAt()).isEqualTo(LocalDateTime.of(2026, 10, 29, 9, 5));
            assertThat(entry.getSentAt()).isNull();
            verifyNoInteractions(emailService);
        }

        @Test
        @DisplayName("DAILY parks the event in the outbox too")
        void should_enqueueOutbox_when_daily() {
            when(preferenceService.resolveOrDefault(recipient, org))
                    .thenReturn(pref(NotificationDelivery.DAILY, LifecycleAction.CREATED));

            sut.processOne(99);

            verify(outboxRepository).save(any(NotificationOutboxEntry.class));
            verifyNoInteractions(emailService);
        }

        @Test
        @DisplayName("a deleted resource is parked with the dashboard URL")
        void should_useDashboardUrl_when_deleted() {
            row.setFirstAction(LifecycleAction.EDITED).setLastAction(LifecycleAction.DELETED);
            when(preferenceService.resolveOrDefault(recipient, org))
                    .thenReturn(pref(NotificationDelivery.BATCHED, LifecycleAction.DELETED));

            sut.processOne(99);

            ArgumentCaptor<NotificationOutboxEntry> captor = ArgumentCaptor.forClass(NotificationOutboxEntry.class);
            verify(outboxRepository).save(captor.capture());
            assertThat(captor.getValue().getAction()).isEqualTo(LifecycleAction.DELETED);
            assertThat(captor.getValue().getResourceUrl()).isEqualTo("https://app.test/dashboard/betlem");
        }

        @Test
        @DisplayName("an unknown actor is named with the i18n fallback")
        void should_useFallbackActorName_when_actorUnknown() {
            row.setActorUserId(null);
            when(memberRepository.findByOrganization(org)).thenReturn(List.of(member(recipient)));
            when(messageSource.getMessage(eq("email.resourceLifecycle.actor.unknown"), any(), anyString(), any()))
                    .thenReturn("Algú");
            when(preferenceService.resolveOrDefault(recipient, org))
                    .thenReturn(pref(NotificationDelivery.BATCHED, LifecycleAction.CREATED));

            sut.processOne(99);

            ArgumentCaptor<NotificationOutboxEntry> captor = ArgumentCaptor.forClass(NotificationOutboxEntry.class);
            verify(outboxRepository).save(captor.capture());
            assertThat(captor.getValue().getActorName()).isEqualTo("Algú");
        }

        @Test
        @DisplayName("a failing instant send does not stop the fan-out nor the row deletion")
        void should_continue_when_sendFails() {
            User other = new User().setId(3).setEmail("other@test").setEmailVerified(true);
            when(memberRepository.findByOrganization(org)).thenReturn(List.of(member(recipient), member(other)));
            when(preferenceService.resolveOrDefault(any(), eq(org)))
                    .thenReturn(pref(NotificationDelivery.INSTANT, LifecycleAction.CREATED));
            doThrow(new IllegalStateException("smtp down")).when(emailService).sendResourceLifecycleEmail(
                    eq("joan@test"), any(), any(), anyString(), anyString(), anyString(), anyString(), any());

            sut.processOne(99);

            verify(emailService).sendResourceLifecycleEmail(eq("other@test"), any(), any(), anyString(),
                    anyString(), anyString(), anyString(), any());
            verify(pendingRepository).delete(row);
        }
    }

    @Nested
    @DisplayName("incrementOrDrop")
    class IncrementOrDrop {

        @Test
        @DisplayName("increments the attempt counter below the limit")
        void should_increment_when_belowMax() {
            row.setAttempts(1);

            sut.incrementOrDrop(99);

            assertThat(row.getAttempts()).isEqualTo(2);
            verify(pendingRepository).save(row);
        }

        @Test
        @DisplayName("drops the row once the limit is reached")
        void should_drop_when_maxReached() {
            row.setAttempts(4);

            sut.incrementOrDrop(99);

            verify(pendingRepository).delete(row);
        }
    }

    @Nested
    @DisplayName("organizationUrl")
    class OrganizationUrl {

        @Test
        @DisplayName("points to the org dashboard, or the generic dashboard without slug")
        void should_buildUrls() {
            assertThat(PendingResourceEventDispatcher.organizationUrl("https://a", "x"))
                    .isEqualTo("https://a/dashboard/x");
            assertThat(PendingResourceEventDispatcher.organizationUrl("https://a", " "))
                    .isEqualTo("https://a/dashboard");
            assertThat(PendingResourceEventDispatcher.organizationUrl("https://a", null))
                    .isEqualTo("https://a/dashboard");
        }
    }
}
