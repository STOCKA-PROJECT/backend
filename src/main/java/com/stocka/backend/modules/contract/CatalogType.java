package com.stocka.backend.modules.contract;

import com.stocka.backend.modules.piecetypes.entity.AttributeType;

/**
 * Parameter and config value types used by the output catalog, and their compatibility with the
 * {@link AttributeType}s a piece-type action parameter can declare. {@code RGB_LIST} is a JSON
 * array of colours (each in any notation {@code RGB} accepts), used by the fade waypoints.
 */
public enum CatalogType {
    INT, NUMBER, BOOL, STRING, RGB, RGB_LIST, ENUM,
    /** The name of a position saved in the {@code moving_head} port the clip drives ({@link HeadPositions}). */
    POSITION,
    /** Config of a {@code moving_head}: the list of its named positions; never an action parameter. */
    POSITIONS;

    /**
     * Parses the lowercase identifier used in {@code catalog.json}.
     *
     * @param id catalog type id, e.g. {@code rgb}
     * @return the matching type
     * @throws IllegalArgumentException when the id is unknown
     */
    public static CatalogType fromCatalogId(String id) {
        return valueOf(id.trim().toUpperCase());
    }

    /**
     * Tells whether a piece-type action parameter of {@code attributeType} can carry a value of this
     * catalog type.
     *
     * @param attributeType the declared attribute type of the action parameter
     * @return {@code true} when compatible
     */
    public boolean accepts(AttributeType attributeType) {
        if (attributeType == null) {
            return false;
        }
        return switch (this) {
            case INT -> attributeType == AttributeType.INTEGER;
            case NUMBER -> attributeType == AttributeType.DECIMAL || attributeType == AttributeType.INTEGER;
            case BOOL -> attributeType == AttributeType.BOOLEAN;
            case STRING, RGB, POSITION -> attributeType == AttributeType.TEXT;
            case RGB_LIST -> attributeType == AttributeType.TEXT || attributeType == AttributeType.LONGTEXT;
            case ENUM -> attributeType == AttributeType.SELECT;
            case POSITIONS -> false;
        };
    }

    /**
     * Human-readable list of the attribute types this catalog type accepts, for error messages.
     *
     * @return e.g. {@code DECIMAL, INTEGER}
     */
    public String acceptedAttributeTypes() {
        return switch (this) {
            case INT -> "INTEGER";
            case NUMBER -> "DECIMAL, INTEGER";
            case BOOL -> "BOOLEAN";
            case STRING, RGB, POSITION -> "TEXT";
            case RGB_LIST -> "TEXT, LONGTEXT";
            case ENUM -> "SELECT";
            case POSITIONS -> "(config only)";
        };
    }
}
