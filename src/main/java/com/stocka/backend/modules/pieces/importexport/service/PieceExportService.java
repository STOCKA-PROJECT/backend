package com.stocka.backend.modules.pieces.importexport.service;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.stocka.backend.modules.common.error.ApiException;
import com.stocka.backend.modules.common.error.ErrorCodes;
import com.stocka.backend.modules.contacts.entity.Contact;
import com.stocka.backend.modules.contacts.service.ContactService;
import com.stocka.backend.modules.locations.entity.Location;
import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.organizations.entity.OrganizationPieceAttribute;
import com.stocka.backend.modules.organizations.repository.OrganizationPieceAttributeRepository;
import com.stocka.backend.modules.organizations.service.OrganizationService;
import com.stocka.backend.modules.pieces.dto.AttributeScope;
import com.stocka.backend.modules.pieces.entity.Piece;
import com.stocka.backend.modules.pieces.entity.PieceAttributeValue;
import com.stocka.backend.modules.pieces.entity.PieceOrganizationAttributeValue;
import com.stocka.backend.modules.pieces.entity.PieceStatus;
import com.stocka.backend.modules.pieces.importexport.config.PieceImportExportProperties;
import com.stocka.backend.modules.pieces.importexport.dto.SpreadsheetFormat;
import com.stocka.backend.modules.pieces.importexport.format.SpreadsheetSerializer;
import com.stocka.backend.modules.pieces.importexport.format.TabularData;
import com.stocka.backend.modules.pieces.repository.PieceAttachmentRepository;
import com.stocka.backend.modules.pieces.service.PieceService;
import com.stocka.backend.modules.piecetypes.entity.AttributeType;
import com.stocka.backend.modules.piecetypes.entity.PieceType;
import com.stocka.backend.modules.piecetypes.entity.PieceTypeAttribute;
import com.stocka.backend.modules.piecetypes.service.PieceTypeService;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Serializes an organization's pieces (optionally filtered) to a flat CSV/XLSX file, and produces
 * the empty header-only template the frontend offers as a starting point. Reuses
 * {@link PieceService} for the filtered query and the same column conventions the import side
 * understands, so an export can be edited and re-imported.
 */
@Service
public class PieceExportService {

    private final PieceService pieceService;
    private final PieceTypeService pieceTypeService;
    private final OrganizationService organizationService;
    private final OrganizationPieceAttributeRepository orgAttributeRepository;
    private final PieceAttachmentRepository attachmentRepository;
    private final RowReferenceResolver referenceResolver;
    private final SpreadsheetSerializer serializer;
    private final PieceImportExportProperties properties;
    private final ObjectMapper json = JsonMapper.builder().build();

    public PieceExportService(
            PieceService pieceService,
            PieceTypeService pieceTypeService,
            OrganizationService organizationService,
            OrganizationPieceAttributeRepository orgAttributeRepository,
            PieceAttachmentRepository attachmentRepository,
            RowReferenceResolver referenceResolver,
            SpreadsheetSerializer serializer,
            PieceImportExportProperties properties
    ) {
        this.pieceService = pieceService;
        this.pieceTypeService = pieceTypeService;
        this.organizationService = organizationService;
        this.orgAttributeRepository = orgAttributeRepository;
        this.attachmentRepository = attachmentRepository;
        this.referenceResolver = referenceResolver;
        this.serializer = serializer;
        this.properties = properties;
    }

    /**
     * Exports the organization's pieces matching the given filters.
     *
     * @param orgId          organization id
     * @param typeId         optional piece-type filter
     * @param locationId     optional location filter
     * @param ownerUserId    optional member-owner filter
     * @param ownerContactId optional contact-owner filter
     * @param status         optional status filter
     * @param q              optional name/description search
     * @param format         target file format
     * @return the serialized file bytes
     * @throws ApiException 422 ({@code pieces.export.too_many_rows}) when the result exceeds the cap
     */
    @Transactional(readOnly = true)
    public byte[] export(Integer orgId, Integer typeId, Integer locationId, Integer ownerUserId,
                         Integer ownerContactId, PieceStatus status, String q, SpreadsheetFormat format) {
        Organization org = organizationService.findById(orgId);
        int cap = properties.getMaxExportRows();
        List<Piece> pieces = pieceService.findAllForExport(orgId, typeId, locationId, ownerUserId,
                ownerContactId, status, q, cap + 1);
        if (pieces.size() > cap) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                    ErrorCodes.PIECES_EXPORT_TOO_MANY_ROWS, Map.of("max", cap));
        }

        Set<PieceType> typesPresent = new LinkedHashSet<>();
        for (Piece piece : pieces) {
            typesPresent.addAll(piece.getPieceTypes());
        }
        List<AttributeColumn> attrColumns = buildColumns(org, typesPresent);
        Map<Integer, Long> attachmentCounts = countAttachments(pieces);

        List<String> headers = headers(attrColumns);
        List<Map<String, String>> rows = new ArrayList<>(pieces.size());
        for (Piece piece : pieces) {
            rows.add(toRow(piece, attrColumns, attachmentCounts));
        }
        return serializer.serialize(new TabularData(headers, rows), format);
    }

    /**
     * Produces an empty file with just the header row (fixed columns plus a column for every
     * attribute currently defined in the organization), used as a fill-in template.
     *
     * @param orgId  organization id
     * @param format target file format
     * @return the serialized header-only file bytes
     */
    @Transactional(readOnly = true)
    public byte[] template(Integer orgId, SpreadsheetFormat format) {
        Organization org = organizationService.findById(orgId);
        List<PieceType> allTypes = pieceTypeService.listAll(orgId);
        List<AttributeColumn> attrColumns = buildColumns(org, allTypes);
        return serializer.serialize(new TabularData(headers(attrColumns), List.of()), format);
    }

    private List<String> headers(List<AttributeColumn> attrColumns) {
        List<String> headers = new ArrayList<>(PieceColumns.FIXED_COLUMNS);
        for (AttributeColumn col : attrColumns) {
            headers.add(col.header());
        }
        return headers;
    }

    /**
     * Builds the ordered attribute columns: every attribute of the given types (each type's
     * attributes in their declared order, types sorted by name) followed by every
     * organization-level attribute.
     */
    private List<AttributeColumn> buildColumns(Organization org, Collection<PieceType> types) {
        List<AttributeColumn> columns = new ArrayList<>();
        types.stream()
                .sorted((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.getName(), b.getName()))
                .forEach(type -> {
                    for (PieceTypeAttribute attr : pieceTypeService.attributesOf(type)) {
                        columns.add(new AttributeColumn(
                                PieceColumns.typeAttributeHeader(type.getName(), attr.getDisplayName()),
                                AttributeScope.TYPE, attr.getId(), attr.getType()));
                    }
                });
        for (OrganizationPieceAttribute attr :
                orgAttributeRepository.findByOrganizationOrderByPositionAscIdAsc(org)) {
            columns.add(new AttributeColumn(
                    PieceColumns.orgAttributeHeader(attr.getDisplayName()),
                    AttributeScope.ORG, attr.getId(), attr.getType()));
        }
        return columns;
    }

    private Map<String, String> toRow(Piece piece, List<AttributeColumn> attrColumns,
                                      Map<Integer, Long> attachmentCounts) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put(PieceColumns.ID, String.valueOf(piece.getId()));
        row.put(PieceColumns.NAME, nullToEmpty(piece.getName()));
        row.put(PieceColumns.SERIAL_NUMBER, nullToEmpty(piece.getSerialNumber()));
        row.put(PieceColumns.DESCRIPTION, nullToEmpty(piece.getDescription()));
        row.put(PieceColumns.STATUS, piece.getStatus() == null ? "" : piece.getStatus().name());
        row.put(PieceColumns.OWNER_EMAIL, piece.getOwner() == null ? "" : piece.getOwner().getEmail());
        row.put(PieceColumns.OWNER_CONTACT, ownerContactCell(piece));
        row.put(PieceColumns.LOCATION_PATH, locationPath(piece.getLocation()));
        row.put(PieceColumns.PIECE_TYPES, typeNames(piece));
        row.put(PieceColumns.ATTACHMENTS_COUNT,
                String.valueOf(attachmentCounts.getOrDefault(piece.getId(), 0L)));
        row.put(PieceColumns.CREATED_AT, isoOrEmpty(piece.getCreatedAt()));
        row.put(PieceColumns.UPDATED_AT, isoOrEmpty(piece.getUpdatedAt()));

        Map<Integer, String> typeValues = new HashMap<>();
        for (PieceAttributeValue v : pieceService.valuesOf(piece)) {
            typeValues.put(v.getAttribute().getId(), v.getValue());
        }
        Map<Integer, String> orgValues = new HashMap<>();
        for (PieceOrganizationAttributeValue v : pieceService.orgValuesOf(piece)) {
            orgValues.put(v.getAttribute().getId(), v.getValue());
        }
        for (AttributeColumn col : attrColumns) {
            String stored = col.scope() == AttributeScope.ORG
                    ? orgValues.get(col.attributeId())
                    : typeValues.get(col.attributeId());
            row.put(col.header(), renderAttributeValue(col.type(), stored));
        }
        return row;
    }

    /**
     * Renders a stored attribute value into its human-readable cell form: {@code MEMBER} ids become
     * e-mails and {@code MULTI_SELECT} JSON arrays become comma-separated lists. Everything else is
     * written verbatim.
     */
    private String renderAttributeValue(AttributeType type, String stored) {
        if (stored == null || stored.isEmpty()) {
            return "";
        }
        return switch (type) {
            case MEMBER -> renderMember(stored);
            case MULTI_SELECT -> renderMultiSelect(stored);
            default -> stored;
        };
    }

    private String renderMember(String stored) {
        try {
            return referenceResolver.emailOfUserId(Integer.valueOf(stored.trim()));
        } catch (NumberFormatException e) {
            return stored;
        }
    }

    private String renderMultiSelect(String stored) {
        try {
            List<?> values = json.readValue(stored, List.class);
            List<String> parts = new ArrayList<>(values.size());
            for (Object value : values) {
                parts.add(String.valueOf(value));
            }
            return String.join(", ", parts);
        } catch (RuntimeException e) {
            return stored;
        }
    }

    private Map<Integer, Long> countAttachments(List<Piece> pieces) {
        Map<Integer, Long> counts = new HashMap<>();
        if (pieces.isEmpty()) {
            return counts;
        }
        for (Object[] tuple : attachmentRepository.countActiveGroupedByPiece(pieces)) {
            counts.put((Integer) tuple[0], (Long) tuple[1]);
        }
        return counts;
    }

    /**
     * Cell form of a contact owner: the contact's e-mail when it has one (the most stable
     * re-import key), otherwise its display name. Empty when the piece has no contact owner.
     */
    private static String ownerContactCell(Piece piece) {
        Contact contact = piece.getOwnerContact();
        if (contact == null) {
            return "";
        }
        String email = contact.getEmail();
        return (email == null || email.isBlank()) ? nullToEmpty(ContactService.displayName(contact)) : email;
    }

    private String typeNames(Piece piece) {
        return piece.getPieceTypes().stream()
                .map(PieceType::getName)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .reduce((a, b) -> a + PieceColumns.TYPE_SEPARATOR + b)
                .orElse("");
    }

    private String locationPath(Location location) {
        if (location == null) {
            return "";
        }
        Deque<String> parts = new ArrayDeque<>();
        Location current = location;
        int guard = 0;
        while (current != null && guard++ < 100) {
            parts.addFirst(current.getName());
            current = current.getParent();
        }
        return String.join(PieceColumns.PATH_SEPARATOR, parts);
    }

    private static String isoOrEmpty(java.util.Date date) {
        return date == null ? "" : date.toInstant().toString();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * One attribute column in the export plan.
     *
     * @param header      the column header
     * @param scope       type-level or organization-level
     * @param attributeId the attribute id used to look up the piece's stored value
     * @param type        the attribute data type, driving how the stored value is rendered
     */
    private record AttributeColumn(String header, AttributeScope scope, Integer attributeId,
                                   AttributeType type) {
    }
}
