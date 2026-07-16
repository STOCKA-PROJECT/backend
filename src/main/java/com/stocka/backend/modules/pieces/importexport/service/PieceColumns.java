package com.stocka.backend.modules.pieces.importexport.service;

import java.util.List;
import java.util.Locale;

/**
 * Column-naming conventions shared by the import and export sides so a file produced by export can
 * be re-imported without edits.
 *
 * <p>Fixed columns have stable snake_case names. The owner spans two mutually-exclusive columns:
 * {@link #OWNER_EMAIL} references an organization member by e-mail and {@link #OWNER_CONTACT}
 * references an external contact by e-mail or display name. Attribute columns are qualified with
 * their scope so the same display name can appear in several types without colliding:
 * <ul>
 *   <li>type-level attribute → {@code "<TypeName> / <DisplayName>"}</li>
 *   <li>organization-level attribute → {@code "Org / <DisplayName>"}</li>
 * </ul>
 */
public final class PieceColumns {

    public static final String ID = "id";
    public static final String NAME = "name";
    public static final String SERIAL_NUMBER = "serial_number";
    public static final String DESCRIPTION = "description";
    public static final String STATUS = "status";
    public static final String OWNER_EMAIL = "owner_email";
    public static final String OWNER_CONTACT = "owner_contact";
    public static final String LOCATION_PATH = "location_path";
    public static final String PIECE_TYPES = "piece_types";
    public static final String ATTACHMENTS_COUNT = "attachments_count";
    public static final String CREATED_AT = "created_at";
    public static final String UPDATED_AT = "updated_at";

    /** Separator between type names inside the {@link #PIECE_TYPES} cell. */
    public static final String TYPE_SEPARATOR = ";";

    /** Separator between segments of a {@link #LOCATION_PATH} cell (e.g. {@code Almacén/Estante}). */
    public static final String PATH_SEPARATOR = "/";

    /** Prefix used to qualify organization-level attribute columns. */
    public static final String ORG_PREFIX = "Org";

    /** Fixed columns, in the order they appear in an export. */
    public static final List<String> FIXED_COLUMNS = List.of(
            ID, NAME, SERIAL_NUMBER, DESCRIPTION, STATUS, OWNER_EMAIL, OWNER_CONTACT, LOCATION_PATH,
            PIECE_TYPES, ATTACHMENTS_COUNT, CREATED_AT, UPDATED_AT);

    private static final List<String> NORMALIZED_FIXED = FIXED_COLUMNS.stream()
            .map(PieceColumns::normalize)
            .toList();

    private PieceColumns() {
        // utility class
    }

    /**
     * @param header a column header from an uploaded file
     * @return {@code true} when the header denotes one of the fixed (non-attribute) columns
     */
    public static boolean isFixed(String header) {
        return NORMALIZED_FIXED.contains(normalize(header));
    }

    /**
     * @param typeName    owning piece-type name
     * @param displayName attribute display name
     * @return the column header for a type-level attribute
     */
    public static String typeAttributeHeader(String typeName, String displayName) {
        return typeName + " / " + displayName;
    }

    /**
     * @param displayName attribute display name
     * @return the column header for an organization-level attribute
     */
    public static String orgAttributeHeader(String displayName) {
        return ORG_PREFIX + " / " + displayName;
    }

    /**
     * Canonical comparison form for a header: trimmed, lowercased and with internal whitespace
     * collapsed. Used so {@code "Tool / Color"} and {@code "tool /  color"} match the same column.
     *
     * @param value a raw header; may be {@code null}
     * @return the normalized form (empty string for {@code null})
     */
    public static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
