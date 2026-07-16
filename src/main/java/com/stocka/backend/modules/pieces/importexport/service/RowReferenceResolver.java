package com.stocka.backend.modules.pieces.importexport.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.stocka.backend.modules.contacts.entity.Contact;
import com.stocka.backend.modules.contacts.repository.ContactRepository;
import com.stocka.backend.modules.contacts.service.ContactService;
import com.stocka.backend.modules.locations.entity.Location;
import com.stocka.backend.modules.locations.repository.LocationRepository;
import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.organizations.entity.OrganizationMember;
import com.stocka.backend.modules.organizations.entity.OrganizationPieceAttribute;
import com.stocka.backend.modules.organizations.entity.OrganizationRoleEnum;
import com.stocka.backend.modules.organizations.repository.OrganizationMemberRepository;
import com.stocka.backend.modules.organizations.repository.OrganizationPieceAttributeRepository;
import com.stocka.backend.modules.pieces.dto.AttributeScope;
import com.stocka.backend.modules.piecetypes.entity.AttributeType;
import com.stocka.backend.modules.piecetypes.entity.PieceType;
import com.stocka.backend.modules.piecetypes.entity.PieceTypeAttribute;
import com.stocka.backend.modules.piecetypes.repository.PieceTypeRepository;
import com.stocka.backend.modules.piecetypes.service.PieceTypeService;
import com.stocka.backend.modules.users.entity.User;
import com.stocka.backend.modules.users.repository.UserRepository;

/**
 * Translates the human-readable references in an import file (owner e-mail, location path, type
 * names, attribute columns and {@code MEMBER} cells) into the database ids the piece service
 * expects. Every "not found" condition raises a {@link RowValidationException} carrying a Spanish
 * message so the import service can report it against the offending row.
 */
@Service
public class RowReferenceResolver {

    private final UserRepository userRepository;
    private final OrganizationMemberRepository memberRepository;
    private final LocationRepository locationRepository;
    private final PieceTypeRepository pieceTypeRepository;
    private final PieceTypeService pieceTypeService;
    private final OrganizationPieceAttributeRepository orgAttributeRepository;
    private final ContactRepository contactRepository;

    public RowReferenceResolver(
            UserRepository userRepository,
            OrganizationMemberRepository memberRepository,
            LocationRepository locationRepository,
            PieceTypeRepository pieceTypeRepository,
            PieceTypeService pieceTypeService,
            OrganizationPieceAttributeRepository orgAttributeRepository,
            ContactRepository contactRepository
    ) {
        this.userRepository = userRepository;
        this.memberRepository = memberRepository;
        this.locationRepository = locationRepository;
        this.pieceTypeRepository = pieceTypeRepository;
        this.pieceTypeService = pieceTypeService;
        this.orgAttributeRepository = orgAttributeRepository;
        this.contactRepository = contactRepository;
    }

    /**
     * Builds the lookup from a file's attribute-column header to the attribute it targets, covering
     * every type-level and organization-level attribute currently defined in the organization. Both
     * the display-name and the technical-name forms of each header are accepted; the display-name
     * form wins on collision.
     *
     * @param org the organization whose schema drives the index
     * @return the immutable index used by {@link #matchAttributeColumn}
     */
    public AttributeColumnIndex buildAttributeIndex(Organization org) {
        Map<String, ResolvedAttributeColumn> byHeader = new LinkedHashMap<>();
        for (PieceType type : pieceTypeRepository.findByOrganization(org)) {
            for (PieceTypeAttribute attr : pieceTypeService.attributesOf(type)) {
                ResolvedAttributeColumn col = new ResolvedAttributeColumn(
                        AttributeScope.TYPE, attr.getId(), attr.getType());
                byHeader.put(PieceColumns.normalize(
                        PieceColumns.typeAttributeHeader(type.getName(), attr.getDisplayName())), col);
                byHeader.putIfAbsent(PieceColumns.normalize(
                        PieceColumns.typeAttributeHeader(type.getName(), attr.getName())), col);
            }
        }
        for (OrganizationPieceAttribute attr :
                orgAttributeRepository.findByOrganizationOrderByPositionAscIdAsc(org)) {
            ResolvedAttributeColumn col = new ResolvedAttributeColumn(
                    AttributeScope.ORG, attr.getId(), attr.getType());
            byHeader.put(PieceColumns.normalize(
                    PieceColumns.orgAttributeHeader(attr.getDisplayName())), col);
            byHeader.putIfAbsent(PieceColumns.normalize(
                    PieceColumns.orgAttributeHeader(attr.getName())), col);
        }
        return new AttributeColumnIndex(byHeader);
    }

    /**
     * @param index  the index built by {@link #buildAttributeIndex}
     * @param header a column header from the file
     * @return the attribute the header targets, or {@code null} when it is neither a fixed column
     *         nor a known attribute
     */
    public ResolvedAttributeColumn matchAttributeColumn(AttributeColumnIndex index, String header) {
        return index.byHeader.get(PieceColumns.normalize(header));
    }

    /**
     * Resolves a piece owner from an e-mail cell.
     *
     * @param org   owning organization
     * @param email raw {@code owner_email} cell; blank means "no owner"
     * @return the owner's user id, or {@code null} when blank
     * @throws RowValidationException when the e-mail matches no user, no member, or a SPECTATOR
     */
    public Integer resolveOwnerUserId(Organization org, String email) {
        if (isBlank(email)) {
            return null;
        }
        String trimmed = email.trim();
        User user = userRepository.findByEmail(trimmed)
                .orElseThrow(() -> new RowValidationException(
                        "No existe ningún usuario con el email '" + trimmed + "'"));
        OrganizationMember member = memberRepository.findByUserAndOrganization(user, org)
                .orElseThrow(() -> new RowValidationException(
                        "El usuario '" + trimmed + "' no es miembro de la organización"));
        if (member.getRole() == OrganizationRoleEnum.SPECTATOR) {
            throw new RowValidationException(
                    "Un espectador no puede ser propietario de un artículo: '" + trimmed + "'");
        }
        return user.getId();
    }

    /**
     * Resolves a contact owner from an {@code owner_contact} cell. The cell is matched (1) against
     * the organization's contact e-mails (case-insensitive) and, when no e-mail matches, (2)
     * against their display names ({@code "First Last"} or the bare first name; whitespace
     * collapsed, case-insensitive). Contacts are never auto-created — a typo must not silently
     * mint a directory entry.
     *
     * @param org  owning organization
     * @param cell raw {@code owner_contact} cell; blank means "no contact owner"
     * @return the contact id, or {@code null} when blank
     * @throws RowValidationException when the cell matches no contact, or its name form matches
     *                                more than one contact (ambiguous — use the e-mail instead)
     */
    public Integer resolveOwnerContactId(Organization org, String cell) {
        if (isBlank(cell)) {
            return null;
        }
        String trimmed = cell.trim();
        Contact byEmail = contactRepository.findFirstByOrganizationAndEmailIgnoreCase(org, trimmed)
                .orElse(null);
        if (byEmail != null) {
            return byEmail.getId();
        }
        String needle = PieceColumns.normalize(trimmed);
        List<Contact> byName = contactRepository.findByOrganizationOrderByNameAscIdAsc(org).stream()
                .filter(c -> needle.equals(PieceColumns.normalize(ContactService.displayName(c))))
                .toList();
        if (byName.size() > 1) {
            throw new RowValidationException(
                    "El contacto '" + trimmed + "' es ambiguo (hay varios con ese nombre); usa su email");
        }
        if (byName.isEmpty()) {
            throw new RowValidationException(
                    "No existe ningún contacto '" + trimmed + "' en la organización");
        }
        return byName.get(0).getId();
    }

    /**
     * Resolves a user id from an e-mail for a {@code MEMBER} attribute cell. Membership and role
     * eligibility are re-checked later by the attribute validator.
     *
     * @param email raw cell value (an e-mail)
     * @return the matching user id
     * @throws RowValidationException when no user has that e-mail
     */
    public Integer resolveUserIdByEmail(String email) {
        String trimmed = email == null ? "" : email.trim();
        return userRepository.findByEmail(trimmed)
                .orElseThrow(() -> new RowValidationException(
                        "No existe ningún usuario con el email '" + trimmed + "'"))
                .getId();
    }

    /**
     * Resolves a location from a {@code "Almacén/Estante/Caja"} path, walking the tree segment by
     * segment within the organization.
     *
     * @param org  owning organization
     * @param path raw {@code location_path} cell; blank means "no location"
     * @return the leaf location id, or {@code null} when blank
     * @throws RowValidationException when any path segment does not exist
     */
    public Integer resolveLocationId(Organization org, String path) {
        if (isBlank(path)) {
            return null;
        }
        List<String> segments = Arrays.stream(path.split(PieceColumns.PATH_SEPARATOR))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        if (segments.isEmpty()) {
            return null;
        }
        String root = segments.get(0);
        Location parent = locationRepository.findByOrganizationAndParentIsNullAndName(org, root)
                .orElseThrow(() -> new RowValidationException(
                        "No existe la ubicación '" + path + "' (no se encontró '" + root + "')"));
        for (int i = 1; i < segments.size(); i++) {
            String segment = segments.get(i);
            Location child = parent;
            parent = locationRepository.findByOrganizationAndParentAndName(org, child, segment)
                    .orElseThrow(() -> new RowValidationException(
                            "No existe la ubicación '" + path + "' (no se encontró '" + segment + "')"));
        }
        return parent.getId();
    }

    /**
     * Resolves a {@code piece_types} cell ({@code "Tool;Heavy"}) into a deduplicated list of type
     * ids in file order.
     *
     * @param org  owning organization
     * @param cell raw cell value; blank means "no types"
     * @return the resolved type ids (possibly empty)
     * @throws RowValidationException when any type name is unknown in the organization
     */
    public List<Integer> resolveTypeIds(Organization org, String cell) {
        if (isBlank(cell)) {
            return List.of();
        }
        Set<String> names = Arrays.stream(cell.split(PieceColumns.TYPE_SEPARATOR))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<Integer> ids = new ArrayList<>(names.size());
        for (String name : names) {
            PieceType type = pieceTypeRepository.findByOrganizationAndName(org, name)
                    .orElseThrow(() -> new RowValidationException(
                            "No existe el tipo de artículo '" + name + "'"));
            ids.add(type.getId());
        }
        return ids;
    }

    /**
     * Reverse lookup used by the export side to render a {@code MEMBER} value (stored as a user id)
     * back into an e-mail.
     *
     * @param userId stored user id; may be {@code null}
     * @return the user's e-mail, or the id as a string when the user cannot be found / is null
     */
    public String emailOfUserId(Integer userId) {
        if (userId == null) {
            return "";
        }
        return userRepository.findById(userId)
                .map(User::getEmail)
                .orElse(String.valueOf(userId));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Immutable lookup from a normalized attribute-column header to the attribute it targets.
     *
     * @see #buildAttributeIndex
     */
    public static final class AttributeColumnIndex {
        private final Map<String, ResolvedAttributeColumn> byHeader;

        private AttributeColumnIndex(Map<String, ResolvedAttributeColumn> byHeader) {
            this.byHeader = Map.copyOf(byHeader);
        }
    }

    /**
     * The attribute an import column maps to.
     *
     * @param scope       whether {@code attributeId} is a type-level or organization-level attribute
     * @param attributeId the targeted attribute id
     * @param type        the attribute's data type (drives e-mail→id translation for {@code MEMBER})
     */
    public record ResolvedAttributeColumn(AttributeScope scope, Integer attributeId, AttributeType type) {
    }
}
