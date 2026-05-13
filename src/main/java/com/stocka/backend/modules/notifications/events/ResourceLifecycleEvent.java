package com.stocka.backend.modules.notifications.events;

import com.stocka.backend.modules.notifications.preferences.entity.LifecycleAction;

/**
 * Application event published by the domain services (PieceService, LocationService,
 * PieceTypeService) whenever a notifiable resource is created, edited or soft-deleted.
 *
 * <p>The event carries everything the dispatch listener needs to coalesce and to render
 * an email without touching the domain again: actor and owner are captured as IDs at
 * publish time (the security context is not reliable from {@code @Async} threads), and
 * {@code resourceName} is the snapshot at the moment of the action so a renamed resource
 * still shows up with its current name in the eventual email.
 */
public record ResourceLifecycleEvent(
        Integer organizationId,
        ResourceKind kind,
        LifecycleAction action,
        Integer resourceId,
        String resourceName,
        Integer actorUserId,
        Integer ownerUserId
) {
}
