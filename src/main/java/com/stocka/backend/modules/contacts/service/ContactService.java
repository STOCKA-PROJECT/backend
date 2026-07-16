package com.stocka.backend.modules.contacts.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.stocka.backend.modules.common.error.ApiException;
import com.stocka.backend.modules.common.error.ErrorCodes;
import com.stocka.backend.modules.contacts.dto.CreateContactDto;
import com.stocka.backend.modules.contacts.dto.LinkContactDto;
import com.stocka.backend.modules.contacts.dto.UpdateContactDto;
import com.stocka.backend.modules.contacts.entity.Contact;
import com.stocka.backend.modules.contacts.repository.ContactRepository;
import com.stocka.backend.modules.notifications.events.ResourceKind;
import com.stocka.backend.modules.notifications.events.ResourceLifecycleEvent;
import com.stocka.backend.modules.notifications.preferences.entity.LifecycleAction;
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

/**
 * CRUD and user-linking for {@link Contact} (the per-organization directory of external people
 * that can own pieces without being registered users or organization members).
 *
 * <p>Deleting a contact is blocked while it still owns non-deleted pieces: {@code Piece.ownerContact}
 * is an eager association to an entity carrying {@code @SQLRestriction("deleted_at IS NULL")}, so a
 * live piece pointing at a soft-deleted contact would break hydration. Callers must reassign or
 * clear the owner of those pieces first.
 */
@Service
public class ContactService {
    private static final int MAX_NAME_LENGTH = 100;
    private static final int MAX_EMAIL_LENGTH = 255;
    private static final int MAX_PHONE_LENGTH = 40;

    private final OrganizationService organizationService;
    private final ContactRepository contactRepository;
    private final UserRepository userRepository;
    private final OrganizationMemberRepository memberRepository;
    private final PieceRepository pieceRepository;
    private final PieceHistoryService historyService;
    private final ApplicationEventPublisher events;

    public ContactService(
            OrganizationService organizationService,
            ContactRepository contactRepository,
            UserRepository userRepository,
            OrganizationMemberRepository memberRepository,
            PieceRepository pieceRepository,
            PieceHistoryService historyService,
            ApplicationEventPublisher events
    ) {
        this.organizationService = organizationService;
        this.contactRepository = contactRepository;
        this.userRepository = userRepository;
        this.memberRepository = memberRepository;
        this.pieceRepository = pieceRepository;
        this.historyService = historyService;
        this.events = events;
    }

    /**
     * Lists the organization's contacts ordered by name, optionally filtered by a free-text query
     * matched (case-insensitive, contains) against name, last name and email.
     *
     * @param orgId organization id
     * @param q     optional filter; blank means "no filter"
     * @return matching active contacts
     */
    public List<Contact> listAll(Integer orgId, String q) {
        Organization org = organizationService.findById(orgId);
        List<Contact> all = contactRepository.findByOrganizationOrderByNameAscIdAsc(org);
        if (q == null || q.isBlank()) {
            return all;
        }
        String needle = q.trim().toLowerCase(Locale.ROOT);
        return all.stream()
                .filter(c -> containsIgnoreCase(c.getName(), needle)
                        || containsIgnoreCase(c.getLastName(), needle)
                        || containsIgnoreCase(c.getEmail(), needle))
                .toList();
    }

    /**
     * Resolves a contact that must belong to the given organization.
     *
     * @param orgId     organization id
     * @param contactId contact id
     * @return the matching contact
     * @throws ApiException 404 ({@link ErrorCodes#CONTACTS_NOT_FOUND}) when missing or in another org
     */
    public Contact findInOrg(Integer orgId, Integer contactId) {
        Organization org = organizationService.findById(orgId);
        return contactRepository.findByIdAndOrganization(contactId, org)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.CONTACTS_NOT_FOUND));
    }

    /**
     * Adds a person to the organization's contact directory.
     *
     * @param orgId organization id
     * @param dto   create payload
     * @return the persisted contact
     */
    @Transactional
    public Contact create(Integer orgId, CreateContactDto dto) {
        Organization org = organizationService.findById(orgId);
        String name = sanitizeName(dto.getName());
        String email = sanitizeEmail(dto.getEmail());
        ensureUniqueEmail(org, email, null);

        Contact contact = new Contact()
                .setOrganization(org)
                .setName(name)
                .setLastName(sanitizeOptional(dto.getLastName(), MAX_NAME_LENGTH, "apellido"))
                .setEmail(email)
                .setPhone(sanitizeOptional(dto.getPhone(), MAX_PHONE_LENGTH, "teléfono"))
                .setNotes(trimToNull(dto.getNotes()));
        return contactRepository.save(contact);
    }

    /**
     * Applies a partial update to a contact. {@code null} fields are left unchanged; blank values
     * clear the optional fields (last name, email, phone, notes).
     *
     * @param orgId     organization id
     * @param contactId contact id
     * @param dto       partial update payload
     * @return the persisted contact
     */
    @Transactional
    public Contact update(Integer orgId, Integer contactId, UpdateContactDto dto) {
        Contact contact = findInOrg(orgId, contactId);

        if (dto.getName() != null) {
            contact.setName(sanitizeName(dto.getName()));
        }
        if (dto.getLastName() != null) {
            contact.setLastName(sanitizeOptional(dto.getLastName(), MAX_NAME_LENGTH, "apellido"));
        }
        if (dto.getEmail() != null) {
            String email = sanitizeEmail(dto.getEmail());
            ensureUniqueEmail(contact.getOrganization(), email, contact.getId());
            contact.setEmail(email);
        }
        if (dto.getPhone() != null) {
            contact.setPhone(sanitizeOptional(dto.getPhone(), MAX_PHONE_LENGTH, "teléfono"));
        }
        if (dto.getNotes() != null) {
            contact.setNotes(trimToNull(dto.getNotes()));
        }
        return contactRepository.save(contact);
    }

    /**
     * Soft-deletes a contact.
     *
     * @param orgId     organization id
     * @param contactId contact id
     * @throws ApiException 409 ({@link ErrorCodes#CONTACTS_OWNS_PIECES}) while the contact still
     *                      owns non-deleted pieces — their owner must be reassigned or cleared first
     */
    @Transactional
    public void softDelete(Integer orgId, Integer contactId) {
        Contact contact = findInOrg(orgId, contactId);
        if (pieceRepository.existsByOwnerContact(contact)) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.CONTACTS_OWNS_PIECES);
        }
        contact.setDeletedAt(LocalDateTime.now());
        contactRepository.save(contact);
    }

    /**
     * Links a contact to an organization member (the external person registered and joined the
     * organization). Optionally migrates the pieces owned by the contact to regular user ownership
     * of the member, recording an {@code OWNER_CHANGED} history entry per migrated piece.
     *
     * @param orgId     organization id
     * @param contactId contact id
     * @param dto       target member's user id plus the optional {@code migratePieces} flag
     * @return the number of pieces migrated (0 when migration was not requested)
     * @throws ApiException 400 when the user is not an organization member, 400 when migration is
     *                      requested and the member is a SPECTATOR (spectators cannot own pieces),
     *                      409 when the contact is already linked or another contact of the
     *                      organization is already linked to that user
     */
    @Transactional
    public int link(Integer orgId, Integer contactId, LinkContactDto dto) {
        Contact contact = findInOrg(orgId, contactId);
        Organization org = contact.getOrganization();
        if (contact.getLinkedUser() != null) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.CONTACTS_ALREADY_LINKED);
        }
        if (dto.getUserId() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.CONTACTS_LINK_USER_NOT_MEMBER);
        }
        User user = userRepository.findById(dto.getUserId())
                .orElseThrow(() -> new ApiException(
                        HttpStatus.BAD_REQUEST, ErrorCodes.CONTACTS_LINK_USER_NOT_MEMBER));
        OrganizationMember membership = memberRepository.findByUserAndOrganization(user, org)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.BAD_REQUEST, ErrorCodes.CONTACTS_LINK_USER_NOT_MEMBER));
        if (contactRepository.existsByOrganizationAndLinkedUser(org, user)) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.CONTACTS_USER_ALREADY_LINKED);
        }

        boolean migrate = Boolean.TRUE.equals(dto.getMigratePieces());
        if (migrate && membership.getRole() == OrganizationRoleEnum.SPECTATOR) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.CONTACTS_LINK_SPECTATOR_CANNOT_OWN);
        }

        contact.setLinkedUser(user);
        contactRepository.save(contact);

        if (!migrate) {
            return 0;
        }
        User actor = currentUser();
        List<Piece> owned = pieceRepository.findByOwnerContact(contact);
        for (Piece piece : owned) {
            piece.setOwner(user).setOwnerContact(null);
            pieceRepository.save(piece);
            historyService.recordOwnerChanged(piece, actor, displayName(contact), displayUserName(user));
            events.publishEvent(new ResourceLifecycleEvent(
                    org.getId(),
                    ResourceKind.PIECE,
                    LifecycleAction.EDITED,
                    piece.getId(),
                    piece.getName(),
                    actor == null ? null : actor.getId(),
                    user.getId()
            ));
        }
        return owned.size();
    }

    /**
     * Clears the linked user of a contact (undo of {@link #link}). Pieces already migrated to the
     * user are not touched.
     *
     * @param orgId     organization id
     * @param contactId contact id
     * @return the updated contact
     */
    @Transactional
    public Contact unlink(Integer orgId, Integer contactId) {
        Contact contact = findInOrg(orgId, contactId);
        contact.setLinkedUser(null);
        return contactRepository.save(contact);
    }

    /**
     * Human-readable display name of a contact ({@code "First Last"}), falling back to the e-mail
     * when both name fields are blank, or {@code null} for a {@code null} contact. Used for audit
     * entries and spreadsheet cells so the value stays meaningful if the contact is later renamed.
     *
     * @param contact the contact whose display name to resolve
     * @return the display name, the e-mail as fallback, or {@code null} if {@code contact} is null
     */
    public static String displayName(Contact contact) {
        if (contact == null) return null;
        String first = contact.getName();
        String last = contact.getLastName();
        String full = ((first == null ? "" : first) + " " + (last == null ? "" : last)).trim();
        if (!full.isEmpty()) return full;
        return contact.getEmail();
    }

    private void ensureUniqueEmail(Organization org, String email, Integer excludeId) {
        if (email == null) return;
        boolean clash = excludeId == null
                ? contactRepository.existsByOrganizationAndEmailIgnoreCase(org, email)
                : contactRepository.existsByOrganizationAndEmailIgnoreCaseAndIdNot(org, email, excludeId);
        if (clash) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.CONTACTS_EMAIL_CONFLICT);
        }
    }

    private String sanitizeName(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.CONTACTS_NAME_REQUIRED);
        }
        String trimmed = raw.trim();
        if (trimmed.length() > MAX_NAME_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El nombre no puede superar " + MAX_NAME_LENGTH + " caracteres");
        }
        return trimmed;
    }

    private String sanitizeEmail(String raw) {
        String email = trimToNull(raw);
        if (email == null) return null;
        if (email.length() > MAX_EMAIL_LENGTH || !email.contains("@")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El email del contacto no es válido");
        }
        return email;
    }

    private String sanitizeOptional(String raw, int maxLength, String fieldLabel) {
        String value = trimToNull(raw);
        if (value != null && value.length() > maxLength) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El " + fieldLabel + " no puede superar " + maxLength + " caracteres");
        }
        return value;
    }

    private static String trimToNull(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean containsIgnoreCase(String haystack, String lowerNeedle) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(lowerNeedle);
    }

    private static String displayUserName(User user) {
        if (user == null) return null;
        String first = user.getName();
        String last = user.getLastName();
        String full = ((first == null ? "" : first) + " " + (last == null ? "" : last)).trim();
        if (!full.isEmpty()) return full;
        return user.getEmail();
    }

    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof User user)) {
            return null;
        }
        return user;
    }
}
