package com.stocka.backend.modules.notifications.events;

/**
 * Identifies which domain resource a {@link ResourceLifecycleEvent} refers to. Kept
 * separate from the entity enums so the notifications module does not pull in piece /
 * location / piecetype packages just for the discriminator.
 */
public enum ResourceKind {
    PIECE,
    LOCATION,
    PIECE_TYPE
}
