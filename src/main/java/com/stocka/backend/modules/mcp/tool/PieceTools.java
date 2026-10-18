package com.stocka.backend.modules.mcp.tool;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import com.stocka.backend.modules.locations.dto.LocationBreadcrumbItemDto;
import com.stocka.backend.modules.locations.service.LocationService;
import com.stocka.backend.modules.mcp.McpProperties;
import com.stocka.backend.modules.mcp.tool.support.AttributeValueResolver;
import com.stocka.backend.modules.mcp.tool.support.AttributeValueResolver.ValueInput;
import com.stocka.backend.modules.mcp.tool.support.OrgAccess;
import com.stocka.backend.modules.mcp.tool.support.Pages;
import com.stocka.backend.modules.oauth.OAuthScope;
import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.pieces.dto.CreatePieceDto;
import com.stocka.backend.modules.pieces.dto.PieceAttachmentResponseDto;
import com.stocka.backend.modules.pieces.dto.PieceAttributeValueResponseDto;
import com.stocka.backend.modules.pieces.dto.PieceFilterCriteria;
import com.stocka.backend.modules.pieces.dto.PieceHistoryItemDto;
import com.stocka.backend.modules.pieces.dto.PieceListItemDto;
import com.stocka.backend.modules.pieces.dto.PieceOwnerSummaryDto;
import com.stocka.backend.modules.pieces.dto.PieceTypeRefDto;
import com.stocka.backend.modules.pieces.dto.UpdatePieceDto;
import com.stocka.backend.modules.pieces.entity.Piece;
import com.stocka.backend.modules.pieces.entity.PieceStatus;
import com.stocka.backend.modules.pieces.repository.PieceAttachmentRepository;
import com.stocka.backend.modules.pieces.repository.PieceHistoryRepository;
import com.stocka.backend.modules.pieces.service.PieceFilterParamParser;
import com.stocka.backend.modules.pieces.service.PieceService;
import com.stocka.backend.modules.piecetypes.entity.PieceType;
import com.stocka.backend.modules.search.service.SemanticSearchService;

/**
 * Pieces: search, detail, history, create, update, delete.
 */
@Component
public class PieceTools implements ToolSource {

    public record AttributeFilter(
            @Param(value = "Attribute id", required = true) Integer attributeId,
            @Param("TYPE (default) or ORG") String scope,
            @Param(value = "Values to match (any of them)", required = true) List<String> values) {
    }

    public record SearchInput(
            @Param(value = "Organization slug", required = true) String org,
            @Param("Free text. Semantic when the organization is indexed (describe the piece), otherwise matched against name and description") String q,
            @Param("Only pieces having one of these piece type ids") List<Integer> typeIds,
            @Param("Only pieces placed directly in this location id") Integer locationId,
            @Param("Only pieces owned by this user id") Integer ownerUserId,
            @Param("Only pieces owned by this contact id") Integer ownerContactId,
            @Param("PENDING (required attributes missing) or ACTIVE") PieceStatus status,
            @Param("Attribute value filters") List<AttributeFilter> attributes,
            @Param("Zero-based page (default 0)") Integer page,
            @Param("Page size (default 20, max 50)") Integer size) {
    }

    public record SearchResult(
            @Param("Pieces of this page") List<PieceListItemDto> items,
            @Param("Zero-based page") int page,
            @Param("Page size") int size,
            @Param("Total matching pieces") long total,
            @Param("Total pages") int totalPages,
            @Param("Whether q was ranked semantically") boolean semantic) {
    }

    public record PieceInput(
            @Param(value = "Organization slug", required = true) String org,
            @Param(value = "Piece id", required = true) Integer pieceId) {
    }

    public record HistoryInput(
            @Param(value = "Organization slug", required = true) String org,
            @Param(value = "Piece id", required = true) Integer pieceId,
            @Param("Zero-based page (default 0)") Integer page,
            @Param("Page size (default 20, max 50)") Integer size) {
    }

    public record AttributeValue(
            @Param(value = "Attribute name, label or id", required = true) String attribute,
            @Param("TYPE or ORG; omit to resolve by name") String scope,
            @Param(value = "Value as text. SELECT: one option; MULTI_SELECT: JSON array of options; BOOLEAN: true/false; DATE: YYYY-MM-DD; empty string clears", required = true) String value) {
        ValueInput toInput() {
            return new ValueInput(attribute, scope, value);
        }
    }

    public record CreateInput(
            @Param(value = "Organization slug", required = true) String org,
            @Param(value = "Name", required = true) String name,
            @Param("Serial number, unique in the organization") String serialNumber,
            @Param("Free-text description") String description,
            @Param(value = "Piece type ids (at least one)", required = true) List<Integer> pieceTypeIds,
            @Param("Owner user id") Integer ownerUserId,
            @Param("Owner contact id (a contact is an owner who is not a Stocka user)") Integer ownerContactId,
            @Param("Location id") Integer locationId,
            @Param("Attribute values") List<AttributeValue> attributeValues) {
    }

    public record UpdateInput(
            @Param(value = "Organization slug", required = true) String org,
            @Param(value = "Piece id", required = true) Integer pieceId,
            @Param("New name") String name,
            @Param("New serial number") String serialNumber,
            @Param("New description") String description,
            @Param("New set of piece type ids (replaces the current ones)") List<Integer> pieceTypeIds,
            @Param("New owner user id") Integer ownerUserId,
            @Param("New owner contact id") Integer ownerContactId,
            @Param("Remove the owner") Boolean clearOwner,
            @Param("New location id") Integer locationId,
            @Param("Remove the location") Boolean clearLocation,
            @Param("Attachment id to use as cover image") Integer coverAttachmentId,
            @Param("Remove the cover image") Boolean clearCover,
            @Param("Attribute values to set (others stay)") List<AttributeValue> attributeValues) {
    }

    public record PieceDetail(
            @Param("Piece id") Integer id,
            @Param("Name") String name,
            @Param("Serial number") String serialNumber,
            @Param("Description") String description,
            @Param("Piece types") List<PieceTypeRefDto> pieceTypes,
            @Param("Owner (user or contact)") PieceOwnerSummaryDto owner,
            @Param("Location id") Integer locationId,
            @Param("Location path from the root") String locationPath,
            @Param("Cover attachment id") Integer coverAttachmentId,
            @Param("PENDING or ACTIVE") PieceStatus status,
            @Param("Attribute values with their attribute name and type") List<PieceAttributeValueResponseDto> attributeValues,
            @Param("Attachments (images, documents, audio)") List<PieceAttachmentResponseDto> attachments,
            @Param("Created") Date createdAt,
            @Param("Last update") Date updatedAt) {
    }

    private final OrgAccess access;
    private final PieceService pieces;
    private final PieceFilterParamParser filters;
    private final PieceAttachmentRepository attachments;
    private final PieceHistoryRepository history;
    private final LocationService locations;
    private final AttributeValueResolver values;
    private final SemanticSearchService search;
    private final McpProperties props;

    public PieceTools(OrgAccess access, PieceService pieces, PieceFilterParamParser filters,
                      PieceAttachmentRepository attachments, PieceHistoryRepository history, LocationService locations,
                      AttributeValueResolver values, SemanticSearchService search, McpProperties props) {
        this.access = access;
        this.pieces = pieces;
        this.filters = filters;
        this.attachments = attachments;
        this.history = history;
        this.locations = locations;
        this.values = values;
        this.search = search;
        this.props = props;
    }

    @Override
    public List<ToolDefinition<?>> tools() {
        return List.of(
                ToolDefinition.of("pieces_search",
                        "Finds pieces. With q the results are ranked by meaning when the organization has a semantic "
                                + "index (describe what you look for, in any language); otherwise q matches name and "
                                + "description. Combine with type, location, owner, status and attribute filters. Paginated.",
                        SearchInput.class, OAuthScope.READ, ToolHints.READ, this::search).returning(SearchResult.class),
                ToolDefinition.of("piece_get",
                        "Full detail of a piece: description, types, owner, location path, attribute values, attachments.",
                        PieceInput.class, OAuthScope.READ, ToolHints.READ, this::get).returning(PieceDetail.class),
                ToolDefinition.of("piece_history",
                        "Change history of a piece (who changed what and when), newest first. Paginated.",
                        HistoryInput.class, OAuthScope.READ, ToolHints.READ, this::historyOf),
                ToolDefinition.of("piece_create",
                        "Creates a piece. Attribute values can reference attributes by name, label or id. The piece "
                                + "is ACTIVE when every required attribute of its types has a value, PENDING otherwise. "
                                + "Needs OWNER, MANAGER or USER.",
                        CreateInput.class, OAuthScope.INVENTORY_WRITE, ToolHints.CREATE, this::create)
                        .returning(PieceDetail.class),
                ToolDefinition.of("piece_update",
                        "Partial update of a piece: only the given fields change. Attribute values are merged. Use "
                                + "clearOwner / clearLocation / clearCover to unset. Needs OWNER, MANAGER or USER.",
                        UpdateInput.class, OAuthScope.INVENTORY_WRITE, ToolHints.UPDATE, this::update)
                        .returning(PieceDetail.class),
                ToolDefinition.of("piece_delete",
                        "Deletes a piece (soft delete; attachments go with it). Needs OWNER, MANAGER or USER and the "
                                + "delete scope.",
                        PieceInput.class, OAuthScope.DELETE, ToolHints.DELETE, this::delete)
                        .returning(ToolResults.Done.class));
    }

    private SearchResult search(ToolCall<SearchInput> call) {
        SearchInput in = call.input();
        Organization org = access.read(call.caller(), in.org());
        List<String> attr = null;
        if (in.attributes() != null && !in.attributes().isEmpty()) {
            attr = new ArrayList<>();
            for (AttributeFilter f : in.attributes()) {
                attr.add(encodeFilter(f));
            }
        }
        PieceFilterCriteria criteria = filters.parse(null, in.typeIds(), in.locationId(), in.ownerUserId(),
                in.ownerContactId(), in.status(), in.q(), attr);
        Pageable pageable = Pages.of(in.page(), in.size(), props.getDefaultPageSize(), props.getMaxPageSize(),
                Sort.by(Sort.Direction.DESC, "updatedAt"));
        Page<Piece> page = pieces.list(org.getId(), criteria, pageable);
        List<PieceListItemDto> items = new ArrayList<>();
        for (Piece piece : page.getContent()) {
            items.add(PieceListItemDto.from(piece));
        }
        boolean semantic = in.q() != null && !in.q().isBlank() && semanticAvailable(org);
        return new SearchResult(items, page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages(),
                semantic);
    }

    private boolean semanticAvailable(Organization org) {
        try {
            var status = search.status(org);
            return status.enabled() && status.indexed() && status.indexedPieces() > 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    static String encodeFilter(AttributeFilter f) {
        String scope = f.scope() == null || f.scope().isBlank() ? "TYPE" : f.scope().trim().toUpperCase();
        StringBuilder sb = new StringBuilder(scope).append(':').append(f.attributeId()).append(':');
        List<String> encoded = new ArrayList<>();
        for (String v : f.values() == null ? List.<String>of() : f.values()) {
            encoded.add(URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8));
        }
        return sb.append(String.join("|", encoded)).toString();
    }

    private PieceDetail get(ToolCall<PieceInput> call) {
        Organization org = access.read(call.caller(), call.input().org());
        return detail(pieces.findInOrg(org.getId(), call.input().pieceId()));
    }

    private Object historyOf(ToolCall<HistoryInput> call) {
        Organization org = access.read(call.caller(), call.input().org());
        Piece piece = pieces.findInOrg(org.getId(), call.input().pieceId());
        Pageable pageable = Pages.of(call.input().page(), call.input().size(), props.getDefaultPageSize(),
                props.getMaxPageSize(), Sort.by(Sort.Direction.DESC, "createdAt"));
        return ToolResults.Page.of(history.findByPieceOrderByCreatedAtDescIdDesc(piece, pageable)
                .map(PieceHistoryItemDto::from));
    }

    private PieceDetail create(ToolCall<CreateInput> call) {
        CreateInput in = call.input();
        Organization org = access.writePieces(call.caller(), in.org());
        CreatePieceDto dto = new CreatePieceDto();
        dto.setName(in.name());
        dto.setSerialNumber(in.serialNumber());
        dto.setDescription(in.description());
        dto.setPieceTypeIds(in.pieceTypeIds());
        dto.setOwnerUserId(in.ownerUserId());
        dto.setOwnerContactId(in.ownerContactId());
        dto.setLocationId(in.locationId());
        dto.setAttributeValues(values.resolve(org.getId(), in.pieceTypeIds(), toInputs(in.attributeValues())));
        return detail(pieces.create(org.getId(), dto));
    }

    private PieceDetail update(ToolCall<UpdateInput> call) {
        UpdateInput in = call.input();
        Organization org = access.writePieces(call.caller(), in.org());
        Piece current = pieces.findInOrg(org.getId(), in.pieceId());
        Set<Integer> typeIds = new LinkedHashSet<>();
        if (in.pieceTypeIds() != null) {
            typeIds.addAll(in.pieceTypeIds());
        } else {
            for (PieceType type : current.getPieceTypes()) {
                typeIds.add(type.getId());
            }
        }
        UpdatePieceDto dto = new UpdatePieceDto();
        dto.setName(in.name());
        dto.setSerialNumber(in.serialNumber());
        dto.setDescription(in.description());
        dto.setPieceTypeIds(in.pieceTypeIds());
        dto.setOwnerUserId(in.ownerUserId());
        dto.setOwnerContactId(in.ownerContactId());
        dto.setClearOwner(in.clearOwner());
        dto.setLocationId(in.locationId());
        dto.setClearLocation(in.clearLocation());
        dto.setCoverAttachmentId(in.coverAttachmentId());
        dto.setClearCover(in.clearCover());
        if (in.attributeValues() != null) {
            dto.setAttributeValues(values.resolve(org.getId(), typeIds, toInputs(in.attributeValues())));
        }
        return detail(pieces.update(org.getId(), in.pieceId(), dto));
    }

    private ToolResults.Done delete(ToolCall<PieceInput> call) {
        Organization org = access.writePieces(call.caller(), call.input().org());
        pieces.softDelete(org.getId(), call.input().pieceId());
        return ToolResults.Done.of("Piece " + call.input().pieceId() + " deleted");
    }

    private static List<ValueInput> toInputs(List<AttributeValue> values) {
        List<ValueInput> out = new ArrayList<>();
        if (values != null) {
            for (AttributeValue v : values) {
                out.add(v.toInput());
            }
        }
        return out;
    }

    /** Builds the detail view the same way the REST controller does, plus the location path. */
    PieceDetail detail(Piece piece) {
        List<PieceAttributeValueResponseDto> valueViews = new ArrayList<>();
        pieces.valuesOf(piece).stream().map(PieceAttributeValueResponseDto::fromType).forEach(valueViews::add);
        pieces.orgValuesOf(piece).stream().map(PieceAttributeValueResponseDto::fromOrg).forEach(valueViews::add);
        List<PieceAttachmentResponseDto> attachmentViews = attachments.findByPiece(piece).stream()
                .map(PieceAttachmentResponseDto::from).toList();
        List<PieceTypeRefDto> types = piece.getPieceTypes().stream()
                .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                .map(PieceTypeRefDto::from).toList();
        String path = null;
        if (piece.getLocation() != null) {
            List<String> segments = new ArrayList<>();
            for (LocationBreadcrumbItemDto item : locations.breadcrumb(piece.getLocation())) {
                segments.add(item.name());
            }
            path = String.join(" / ", segments);
        }
        return new PieceDetail(piece.getId(), piece.getName(), piece.getSerialNumber(), piece.getDescription(), types,
                PieceOwnerSummaryDto.from(piece), piece.getLocation() == null ? null : piece.getLocation().getId(), path,
                piece.getCoverAttachment() == null ? null : piece.getCoverAttachment().getId(), piece.getStatus(),
                valueViews, attachmentViews, piece.getCreatedAt(), piece.getUpdatedAt());
    }
}
