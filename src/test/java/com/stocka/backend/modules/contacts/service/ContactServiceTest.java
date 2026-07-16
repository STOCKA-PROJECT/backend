package com.stocka.backend.modules.contacts.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;

import com.stocka.backend.modules.common.error.ApiException;
import com.stocka.backend.modules.common.error.ErrorCodes;
import com.stocka.backend.modules.contacts.dto.CreateContactDto;
import com.stocka.backend.modules.contacts.dto.LinkContactDto;
import com.stocka.backend.modules.contacts.dto.UpdateContactDto;
import com.stocka.backend.modules.contacts.entity.Contact;
import com.stocka.backend.modules.contacts.repository.ContactRepository;
import com.stocka.backend.modules.notifications.events.ResourceLifecycleEvent;
import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.organizations.entity.OrganizationMember;
import com.stocka.backend.modules.organizations.entity.OrganizationRoleEnum;
import com.stocka.backend.modules.organizations.repository.OrganizationMemberRepository;
import com.stocka.backend.modules.organizations.service.OrganizationService;
import com.stocka.backend.modules.pieces.entity.Piece;
import com.stocka.backend.modules.pieces.repository.PieceRepository;
import com.stocka.backend.modules.pieces.service.PieceHistoryService;
import com.stocka.backend.modules.users.entity.User;
import com.stocka.backend.modules.users.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("ContactService")
class ContactServiceTest {

    @Mock private OrganizationService organizationService;
    @Mock private ContactRepository contactRepository;
    @Mock private UserRepository userRepository;
    @Mock private OrganizationMemberRepository memberRepository;
    @Mock private PieceRepository pieceRepository;
    @Mock private PieceHistoryService historyService;
    @Mock private ApplicationEventPublisher events;
    @InjectMocks private ContactService sut;

    private Organization org;

    @BeforeEach
    void setUp() {
        // lenient: the displayName tests never resolve the organization
        org = new Organization().setId(1).setName("Acme").setSlug("acme");
        lenient().when(organizationService.findById(1)).thenReturn(org);
    }

    private Contact existingContact(Integer id) {
        Contact contact = new Contact()
                .setId(id)
                .setOrganization(org)
                .setName("Jane")
                .setLastName("Doe")
                .setEmail("jane@ext.com")
                .setPhone("600111222");
        when(contactRepository.findByIdAndOrganization(id, org)).thenReturn(Optional.of(contact));
        return contact;
    }

    @Nested
    @DisplayName("create")
    class Create {
        @Test
        @DisplayName("should persist a trimmed contact with blank optionals normalized to null")
        void should_persistNormalizedContact() {
            when(contactRepository.save(any(Contact.class))).thenAnswer(inv -> inv.getArgument(0));

            Contact saved = sut.create(1, new CreateContactDto()
                    .setName("  Jane ").setLastName(" ").setEmail("  ").setPhone("").setNotes("  vip "));

            assertThat(saved.getName()).isEqualTo("Jane");
            assertThat(saved.getLastName()).isNull();
            assertThat(saved.getEmail()).isNull();
            assertThat(saved.getPhone()).isNull();
            assertThat(saved.getNotes()).isEqualTo("vip");
            assertThat(saved.getOrganization()).isSameAs(org);
        }

        @Test
        @DisplayName("should reject a blank name with contacts.name_required")
        void should_reject_whenNameBlank() {
            assertThatThrownBy(() -> sut.create(1, new CreateContactDto().setName("  ")))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(e.getCode()).isEqualTo(ErrorCodes.CONTACTS_NAME_REQUIRED);
                    });
        }

        @Test
        @DisplayName("should reject a duplicated email with contacts.email_conflict")
        void should_reject_whenEmailTaken() {
            when(contactRepository.existsByOrganizationAndEmailIgnoreCase(org, "jane@ext.com"))
                    .thenReturn(true);

            assertThatThrownBy(() -> sut.create(1,
                    new CreateContactDto().setName("Jane").setEmail("jane@ext.com")))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(e.getCode()).isEqualTo(ErrorCodes.CONTACTS_EMAIL_CONFLICT);
                    });
        }
    }

    @Nested
    @DisplayName("update")
    class Update {
        @Test
        @DisplayName("should leave null fields unchanged and clear blank optionals")
        void should_applyPartialUpdate() {
            Contact contact = existingContact(5);
            when(contactRepository.save(any(Contact.class))).thenAnswer(inv -> inv.getArgument(0));

            Contact saved = sut.update(1, 5, new UpdateContactDto().setEmail(" ").setPhone("  "));

            assertThat(saved.getName()).isEqualTo("Jane");
            assertThat(saved.getLastName()).isEqualTo("Doe");
            assertThat(saved.getEmail()).isNull();
            assertThat(saved.getPhone()).isNull();
        }

        @Test
        @DisplayName("should reject an email already used by another contact")
        void should_reject_whenEmailTakenByOther() {
            existingContact(5);
            when(contactRepository.existsByOrganizationAndEmailIgnoreCaseAndIdNot(org, "other@ext.com", 5))
                    .thenReturn(true);

            assertThatThrownBy(() -> sut.update(1, 5, new UpdateContactDto().setEmail("other@ext.com")))
                    .isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.CONTACTS_EMAIL_CONFLICT));
        }

        @Test
        @DisplayName("should answer contacts.not_found for a contact of another organization")
        void should_reject_whenContactMissing() {
            when(contactRepository.findByIdAndOrganization(99, org)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> sut.update(1, 99, new UpdateContactDto().setName("X")))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                        assertThat(e.getCode()).isEqualTo(ErrorCodes.CONTACTS_NOT_FOUND);
                    });
        }
    }

    @Nested
    @DisplayName("softDelete")
    class SoftDelete {
        @Test
        @DisplayName("should block deletion while the contact owns pieces")
        void should_block_whenOwnsPieces() {
            Contact contact = existingContact(5);
            when(pieceRepository.existsByOwnerContact(contact)).thenReturn(true);

            assertThatThrownBy(() -> sut.softDelete(1, 5))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(e.getCode()).isEqualTo(ErrorCodes.CONTACTS_OWNS_PIECES);
                    });
            verify(contactRepository, never()).save(any());
        }

        @Test
        @DisplayName("should soft-delete when the contact owns no pieces")
        void should_softDelete_whenNoPieces() {
            Contact contact = existingContact(5);
            when(pieceRepository.existsByOwnerContact(contact)).thenReturn(false);

            sut.softDelete(1, 5);

            assertThat(contact.getDeletedAt()).isNotNull();
            verify(contactRepository).save(contact);
        }
    }

    @Nested
    @DisplayName("link")
    class Link {
        private User member(Integer id, OrganizationRoleEnum role) {
            User user = new User().setId(id).setName("Bob").setLastName("Jones")
                    .setEmail("bob@test.com");
            when(userRepository.findById(id)).thenReturn(Optional.of(user));
            when(memberRepository.findByUserAndOrganization(user, org))
                    .thenReturn(Optional.of(new OrganizationMember()
                            .setUser(user).setOrganization(org).setRole(role)));
            return user;
        }

        @Test
        @DisplayName("should reject when the contact is already linked")
        void should_reject_whenAlreadyLinked() {
            existingContact(5).setLinkedUser(new User().setId(9));

            assertThatThrownBy(() -> sut.link(1, 5, new LinkContactDto().setUserId(2)))
                    .isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.CONTACTS_ALREADY_LINKED));
        }

        @Test
        @DisplayName("should reject when the user is not an organization member")
        void should_reject_whenNotMember() {
            existingContact(5);
            User user = new User().setId(2);
            when(userRepository.findById(2)).thenReturn(Optional.of(user));
            when(memberRepository.findByUserAndOrganization(user, org)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> sut.link(1, 5, new LinkContactDto().setUserId(2)))
                    .isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.CONTACTS_LINK_USER_NOT_MEMBER));
        }

        @Test
        @DisplayName("should reject when another contact is already linked to that user")
        void should_reject_whenUserAlreadyLinked() {
            existingContact(5);
            User user = member(2, OrganizationRoleEnum.USER);
            when(contactRepository.existsByOrganizationAndLinkedUser(org, user)).thenReturn(true);

            assertThatThrownBy(() -> sut.link(1, 5, new LinkContactDto().setUserId(2)))
                    .isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.CONTACTS_USER_ALREADY_LINKED));
        }

        @Test
        @DisplayName("should reject migration to a SPECTATOR member")
        void should_reject_whenSpectatorAndMigrating() {
            existingContact(5);
            User user = member(2, OrganizationRoleEnum.SPECTATOR);
            when(contactRepository.existsByOrganizationAndLinkedUser(org, user)).thenReturn(false);

            assertThatThrownBy(() -> sut.link(1, 5,
                    new LinkContactDto().setUserId(2).setMigratePieces(true)))
                    .isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.getCode())
                                    .isEqualTo(ErrorCodes.CONTACTS_LINK_SPECTATOR_CANNOT_OWN));
        }

        @Test
        @DisplayName("should link without touching pieces when migration is not requested")
        void should_linkOnly_whenNoMigration() {
            Contact contact = existingContact(5);
            User user = member(2, OrganizationRoleEnum.SPECTATOR);
            when(contactRepository.existsByOrganizationAndLinkedUser(org, user)).thenReturn(false);
            when(contactRepository.save(any(Contact.class))).thenAnswer(inv -> inv.getArgument(0));

            int migrated = sut.link(1, 5, new LinkContactDto().setUserId(2));

            assertThat(migrated).isZero();
            assertThat(contact.getLinkedUser()).isSameAs(user);
            verify(pieceRepository, never()).findByOwnerContact(any());
        }

        @Test
        @DisplayName("should migrate owned pieces recording an OWNER_CHANGED entry per piece")
        void should_migratePieces_whenRequested() {
            Contact contact = existingContact(5);
            User user = member(2, OrganizationRoleEnum.USER);
            when(contactRepository.existsByOrganizationAndLinkedUser(org, user)).thenReturn(false);
            when(contactRepository.save(any(Contact.class))).thenAnswer(inv -> inv.getArgument(0));
            Piece p1 = new Piece().setId(31).setName("Hammer").setOrganization(org).setOwnerContact(contact);
            Piece p2 = new Piece().setId(32).setName("Drill").setOrganization(org).setOwnerContact(contact);
            when(pieceRepository.findByOwnerContact(contact)).thenReturn(List.of(p1, p2));

            int migrated = sut.link(1, 5, new LinkContactDto().setUserId(2).setMigratePieces(true));

            assertThat(migrated).isEqualTo(2);
            assertThat(p1.getOwner()).isSameAs(user);
            assertThat(p1.getOwnerContact()).isNull();
            assertThat(p2.getOwner()).isSameAs(user);
            verify(historyService).recordOwnerChanged(p1, null, "Jane Doe", "Bob Jones");
            verify(historyService).recordOwnerChanged(p2, null, "Jane Doe", "Bob Jones");
            verify(events, times(2)).publishEvent(any(ResourceLifecycleEvent.class));
        }
    }

    @Nested
    @DisplayName("unlink")
    class Unlink {
        @Test
        @DisplayName("should clear the linked user")
        void should_clearLinkedUser() {
            Contact contact = existingContact(5).setLinkedUser(new User().setId(2));
            when(contactRepository.save(any(Contact.class))).thenAnswer(inv -> inv.getArgument(0));

            Contact saved = sut.unlink(1, 5);

            assertThat(saved.getLinkedUser()).isNull();
        }
    }

    @Nested
    @DisplayName("displayName")
    class DisplayNameResolution {
        @Test
        @DisplayName("should join first and last name")
        void should_joinNames() {
            assertThat(ContactService.displayName(new Contact().setName("Jane").setLastName("Doe")))
                    .isEqualTo("Jane Doe");
        }

        @Test
        @DisplayName("should fall back to the email when both names are blank")
        void should_fallBackToEmail() {
            assertThat(ContactService.displayName(new Contact().setName(" ").setEmail("j@ext.com")))
                    .isEqualTo("j@ext.com");
        }

        @Test
        @DisplayName("should return null for a null contact")
        void should_returnNull_whenContactNull() {
            assertThat(ContactService.displayName(null)).isNull();
        }
    }
}
