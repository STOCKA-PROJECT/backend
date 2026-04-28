package com.stocka.backend.modules.pieces.entity;

/**
 * Whether a piece has all of its required attributes filled in. Computed automatically; never
 * set by clients.
 */
public enum PieceStatus {
    /** All required attributes have a non-blank value. */
    ACTIVE,
    /** At least one required attribute is missing or blank. */
    PENDING
}
