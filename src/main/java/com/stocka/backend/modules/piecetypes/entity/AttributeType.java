package com.stocka.backend.modules.piecetypes.entity;

/**
 * Supported data types for piece-type attributes. Each value has a matching
 * {@code AttributeValueValidator} strategy in the pieces module that knows how to validate raw
 * input and how to normalize it into the string stored in {@code piece_attribute_values.value}.
 */
public enum AttributeType {
    TEXT,
    LONGTEXT,
    INTEGER,
    DECIMAL,
    PRICE,
    DATE,
    DATETIME,
    BOOLEAN,
    SELECT,
    MULTI_SELECT,
    URL,
    EMAIL
}
