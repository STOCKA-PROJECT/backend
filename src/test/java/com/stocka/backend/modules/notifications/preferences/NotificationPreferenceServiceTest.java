package com.stocka.backend.modules.notifications.preferences;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.server.ResponseStatusException;

import com.stocka.backend.modules.notifications.preferences.dto.NotificationPreferenceResponseDto;
import com.stocka.backend.modules.notifications.preferences.dto.UpdateNotificationPreferenceDto;
import com.stocka.backend.modules.notifications.preferences.entity.LifecycleAction;
import com.stocka.backend.modules.notifications.preferences.entity.NotificationDelivery;
import com.stocka.backend.modules.notifications.preferences.entity.NotificationPreference;
import com.stocka.backend.modules.notifications.preferences.entity.PieceScope;
import com.stocka.backend.modules.notifications.preferences.repository.NotificationPreferenceRepository;
import com.stocka.backend.modules.notifications.preferences.service.NotificationPreferenceService;
import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.organizations.entity.OrganizationMember;
import com.stocka.backend.modules.organizations.repository.OrganizationMemberRepository;
import com.stocka.backend.modules.users.entity.User;

@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationPreferenceService")
class NotificationPreferenceServiceTest {

    @Mock private NotificationPreferenceRepository repository;
    @Mock private OrganizationMemberRepository memberRepository;

    private NotificationPreferenceService sut;
    private User user;
    private Organization org;

    @BeforeEach
    void setUp() {
        sut = new NotificationPreferenceService(repository, memberRepository);
        user = new User().setId(2).setEmail("joan@test");
        org = new Organization().setId(7).setName("Betlem").setSlug("betlem");
    }

    private UpdateNotificationPreferenceDto dto(NotificationDelivery delivery) {
        return new UpdateNotificationPreferenceDto()
                .setPieces(Set.of(LifecycleAction.CREATED))
                .setPieceScope(PieceScope.ALL)
                .setLocations(Set.of())
                .setPieceTypes(Set.of())
                .setDelivery(delivery);
    }

    private void member() {
        when(memberRepository.findByUser(user)).thenReturn(List.of(
                new OrganizationMember().setUser(user).setOrganization(org)));
    }

    @Nested
    @DisplayName("defaults")
    class Defaults {

        @Test
        @DisplayName("a member without a row gets created pieces, owned only, batched")
        void should_resolveDefault_when_noRow() {
            when(repository.findByUserAndOrganization(user, org)).thenReturn(Optional.empty());

            NotificationPreference pref = sut.resolveOrDefault(user, org);

            assertThat(pref.getPieces()).containsExactly(LifecycleAction.CREATED);
            assertThat(pref.getPieceScope()).isEqualTo(PieceScope.OWNED_ONLY);
            assertThat(pref.getLocations()).isEmpty();
            assertThat(pref.getPieceTypes()).isEmpty();
            assertThat(pref.getDelivery()).isEqualTo(NotificationDelivery.BATCHED);
        }

        @Test
        @DisplayName("listing exposes the default delivery for organizations without a row")
        void should_listDefaults() {
            member();
            when(repository.findByUserAndOrganizationIn(any(), any())).thenReturn(List.of());

            List<NotificationPreferenceResponseDto> out = sut.listForUser(user);

            assertThat(out).hasSize(1);
            assertThat(out.getFirst().delivery()).isEqualTo(NotificationDelivery.BATCHED);
            assertThat(out.getFirst().organizationSlug()).isEqualTo("betlem");
        }

        @Test
        @DisplayName("the entity never stores a null delivery")
        void should_normalizeNullDelivery() {
            assertThat(new NotificationPreference().setDelivery(null).getDelivery())
                    .isEqualTo(NotificationDelivery.BATCHED);
        }
    }

    @Nested
    @DisplayName("upsert")
    class Upsert {

        @Test
        @DisplayName("stores the requested delivery")
        void should_storeDelivery() {
            member();
            when(repository.findByUserAndOrganization(user, org)).thenReturn(Optional.empty());
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            NotificationPreferenceResponseDto out = sut.upsert(user, 7, dto(NotificationDelivery.DAILY));

            assertThat(out.delivery()).isEqualTo(NotificationDelivery.DAILY);
        }

        @Test
        @DisplayName("a body without delivery keeps the batched default (older clients)")
        void should_defaultDelivery_when_missing() {
            member();
            NotificationPreference existing = new NotificationPreference().setUser(user).setOrganization(org)
                    .setDelivery(NotificationDelivery.INSTANT);
            when(repository.findByUserAndOrganization(user, org)).thenReturn(Optional.of(existing));
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            NotificationPreferenceResponseDto out = sut.upsert(user, 7, dto(null));

            assertThat(out.delivery()).isEqualTo(NotificationDelivery.BATCHED);
        }

        @Test
        @DisplayName("a lost insert race retries as an update with the delivery")
        void should_retryAsUpdate_when_raceLost() {
            member();
            NotificationPreference winner = new NotificationPreference().setUser(user).setOrganization(org);
            when(repository.findByUserAndOrganization(user, org))
                    .thenReturn(Optional.empty(), Optional.of(winner));
            when(repository.save(any()))
                    .thenThrow(new DataIntegrityViolationException("dup"))
                    .thenAnswer(inv -> inv.getArgument(0));

            NotificationPreferenceResponseDto out = sut.upsert(user, 7, dto(NotificationDelivery.INSTANT));

            assertThat(out.delivery()).isEqualTo(NotificationDelivery.INSTANT);
            assertThat(winner.getDelivery()).isEqualTo(NotificationDelivery.INSTANT);
        }

        @Test
        @DisplayName("rejects a body without the mandatory sets")
        void should_reject_when_setsMissing() {
            UpdateNotificationPreferenceDto bad = dto(NotificationDelivery.BATCHED).setPieces(null);

            assertThatThrownBy(() -> sut.upsert(user, 7, bad)).isInstanceOf(ResponseStatusException.class);
        }

        @Test
        @DisplayName("rejects an organization the user is not a member of")
        void should_reject_when_notMember() {
            when(memberRepository.findByUser(user)).thenReturn(List.of());

            assertThatThrownBy(() -> sut.upsert(user, 7, dto(NotificationDelivery.BATCHED)))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("No eres miembro");
        }

        @Test
        @DisplayName("keeps EnumSet semantics for the action sets")
        void should_copySets() {
            member();
            when(repository.findByUserAndOrganization(user, org)).thenReturn(Optional.empty());
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            NotificationPreferenceResponseDto out = sut.upsert(user, 7,
                    dto(NotificationDelivery.BATCHED).setLocations(EnumSet.allOf(LifecycleAction.class)));

            assertThat(out.locations()).containsExactlyInAnyOrder(LifecycleAction.values());
        }
    }
}
