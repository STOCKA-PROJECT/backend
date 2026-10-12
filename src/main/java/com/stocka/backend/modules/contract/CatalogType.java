package com.stocka.backend.modules.contract;

import com.stocka.backend.modules.piecetypes.entity.AttributeType;

/**
 * Parameter and config value types used by the output catalog, and their compatibility with the
 * {@link AttributeType}s a piece-type action parameter can declare.
 */
public enum CatalogType {
    INT, NUMBER, BOOL, STRING, RGB, ENUM;

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
            case STRING, RGB -> attributeType == AttributeType.TEXT;
            case ENUM -> attributeType == AttributeType.SELECT;
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
            case STRING, RGB -> "TEXT";
            case ENUM -> "SELECT";
        };
    }
}
