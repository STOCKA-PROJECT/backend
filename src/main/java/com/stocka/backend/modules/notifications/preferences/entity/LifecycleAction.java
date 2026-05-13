package com.stocka.backend.modules.notifications.preferences.entity;

/**
 * Action that a domain resource (piece, location, piece type) can undergo
 * inside its lifecycle. Reused both as the per-resource subscription unit in
 * {@code NotificationPreference} and as the action field on the lifecycle
 * event emitted by the corresponding services.
 */
public enum LifecycleAction {
    CREATED,
    EDITED,
    DELETED
}
