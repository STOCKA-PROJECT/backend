package com.stocka.backend.modules.search.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.stocka.backend.modules.contacts.entity.Contact;
import com.stocka.backend.modules.locations.entity.Location;
import com.stocka.backend.modules.pieces.entity.Piece;
import com.stocka.backend.modules.pieces.entity.PieceAttributeValue;
import com.stocka.backend.modules.pieces.entity.PieceOrganizationAttributeValue;
import com.stocka.backend.modules.piecetypes.entity.AttributeType;
import com.stocka.backend.modules.piecetypes.entity.PieceType;
import com.stocka.backend.modules.search.client.XeyeClient.ElementInput;
import com.stocka.backend.modules.users.entity.User;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * What XEYE indexes for one piece.
 *
 * <p>{@code text} is what the search returns and matches first: the name plus the serial number.
 * {@code description} is the context the embedding model and the AI enrichment read: types,
 * free-text description, location path, owner and every attribute value, one per line, so a user
 * can find the piece by describing it ("el pastor de fusta amb l'ovella a l'espatlla").
 * {@code params} carries the piece id back with every hit.
 *
 * @param text        indexed text, at most {@value #TEXT_MAX_LENGTH} characters
 * @param description context lines, at most {@value #DESCRIPTION_MAX_LENGTH} characters, or
 *                    {@code null} when there is nothing beyond the name
 * @param params      {@code {"pieceId": id}}
 */
public record PieceSearchDocument(String text, String description, Map<String, Object> params) {

    /** XEYE cap for {@code text}. */
    public static final int TEXT_MAX_LENGTH = 1000;
    /** XEYE cap for {@code description}. */
    public static final int DESCRIPTION_MAX_LENGTH = 4000;
    /** Guard against a corrupt location tree while walking up to the root. */
    static final int MAX_LOCATION_DEPTH = 50;

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    /**
     * Builds the document of a piece.
     *
     * @param piece     the piece (name, serial, types, location, owner, description)
     * @param values    its type-attribute values
     * @param orgValues its organization-attribute values
     * @return the document to send to XEYE
     */
    public static PieceSearchDocument of(Piece piece, List<PieceAttributeValue> values,
                                         List<PieceOrganizationAttributeValue> orgValues) {
        String text = clip(joinNonBlank(" · ", piece.getName(), piece.getSerialNumber()), TEXT_MAX_LENGTH);

        List<String> lines = new ArrayList<>();
        addLine(lines, "Tipus", piece.getPieceTypes().stream().map(PieceType::getName)
                .filter(PieceSearchDocument::hasText).collect(Collectors.joining(", ")));
        addLine(lines, "Descripció", piece.getDescription());
        addLine(lines, "Ubicació", locationPath(piece.getLocation()));
        addLine(lines, "Propietari", ownerName(piece));
        for (PieceOrganizationAttributeValue value : orgValues) {
            addLine(lines, value.getAttribute().getDisplayName(),
                    displayValue(value.getAttribute().getType(), value.getValue()));
        }
        for (PieceAttributeValue value : values) {
            addLine(lines, value.getAttribute().getDisplayName(),
                    displayValue(value.getAttribute().getType(), value.getValue()));
        }
        String description = clip(String.join("\n", lines), DESCRIPTION_MAX_LENGTH);
        return new PieceSearchDocument(text, description.isEmpty() ? null : description,
                Map.of("pieceId", piece.getId()));
    }

    /** @return this document as the payload of the XEYE element endpoints */
    public ElementInput toElementInput() {
        return new ElementInput(text, description, params);
    }

    /**
     * Root-to-leaf path of a location ({@code "Magatzem / Prestatge 2"}).
     *
     * @param location the leaf, or {@code null}
     * @return the path, or {@code null} without location
     */
    static String locationPath(Location location) {
        if (location == null) return null;
        List<String> names = new ArrayList<>();
        Location current = location;
        for (int depth = 0; current != null && depth < MAX_LOCATION_DEPTH; depth++) {
            if (hasText(current.getName())) names.add(0, current.getName().trim());
            current = current.getParent();
        }
        return names.isEmpty() ? null : String.join(" / ", names);
    }

    /**
     * Human value of a stored attribute: multi-selects become a comma list, booleans "sí"/"no".
     *
     * @param type attribute type
     * @param raw  canonical stored value
     * @return the readable value, or {@code null} when blank
     */
    static String displayValue(AttributeType type, String raw) {
        if (!hasText(raw)) return null;
        String value = raw.trim();
        if (type == AttributeType.MULTI_SELECT) {
            try {
                JsonNode array = JSON.readTree(value);
                if (array.isArray()) {
                    List<String> items = new ArrayList<>();
                    array.forEach(item -> { if (hasText(item.asText())) items.add(item.asText().trim()); });
                    return items.isEmpty() ? null : String.join(", ", items);
                }
            } catch (RuntimeException ignored) {
                // not a JSON array: fall through and index the raw text
            }
        }
        if (type == AttributeType.BOOLEAN) {
            if ("true".equalsIgnoreCase(value)) return "sí";
            if ("false".equalsIgnoreCase(value)) return "no";
        }
        return value;
    }

    private static String ownerName(Piece piece) {
        User user = piece.getOwner();
        if (user != null) {
            String name = joinNonBlank(" ", user.getName(), user.getLastName());
            return hasText(name) ? name : user.getEmail();
        }
        Contact contact = piece.getOwnerContact();
        if (contact != null) {
            String name = joinNonBlank(" ", contact.getName(), contact.getLastName());
            return hasText(name) ? name : contact.getEmail();
        }
        return null;
    }

    private static void addLine(List<String> lines, String label, String value) {
        if (hasText(label) && hasText(value)) lines.add(label.trim() + ": " + value.trim());
    }

    private static String joinNonBlank(String separator, String... parts) {
        List<String> kept = new ArrayList<>();
        for (String part : parts) {
            if (hasText(part)) kept.add(part.trim());
        }
        return String.join(separator, kept);
    }

    private static String clip(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
