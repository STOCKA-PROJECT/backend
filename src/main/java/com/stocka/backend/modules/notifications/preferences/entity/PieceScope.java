package com.stocka.backend.modules.notifications.preferences.entity;

/**
 * Scope filter applied only to piece notifications: either every piece in the
 * organization or just the ones the recipient owns.
 */
public enum PieceScope {
    ALL,
    OWNED_ONLY
}
