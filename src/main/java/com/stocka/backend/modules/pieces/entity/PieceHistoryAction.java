package com.stocka.backend.modules.pieces.entity;

/**
 * Discrete events recorded in {@code piece_history}. Each entry pinpoints the field that changed
 * (when applicable) plus its old and new values.
 */
public enum PieceHistoryAction {
    PIECE_CREATED,
    PIECE_UPDATED,
    PIECE_DELETED,
    OWNER_CHANGED,
    LOCATION_CHANGED,
    STATUS_CHANGED,
    ATTRIBUTE_VALUE_CHANGED,
    ATTACHMENT_ADDED,
    ATTACHMENT_REMOVED
}
