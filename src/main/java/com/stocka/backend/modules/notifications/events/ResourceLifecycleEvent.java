package com.stocka.backend.modules.notifications.events;

import com.stocka.backend.modules.notifications.dispatch.NotificationSuppressionContext;
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
 *
 * <p>{@code replay} marks an event produced while draining a client's offline outbox during
 * sync push; the dispatch listener drops such events so subscribers are not flooded with
 * notifications for changes the user already made locally. It is captured from {@link
 * NotificationSuppressionContext} at publish time (on the request thread), since the async
 * listener cannot see the thread-local.
 */
public record ResourceLifecycleEvent(
        Integer organizationId,
        ResourceKind kind,
        LifecycleAction action,
        Integer resourceId,
        String resourceName,
        Integer actorUserId,
        Integer ownerUserId,
        boolean replay
) {
    /**
     * Creates an event, capturing the current thread's notification-suppression state as {@link
     * #replay()}. Lets the domain services keep publishing with the original seven arguments while
     * sync replay is transparently honoured.
     *
     * @param organizationId the organization the resource belongs to
     * @param kind           the kind of resource
     * @param action         the lifecycle action that occurred
     * @param resourceId     the resource id
     * @param resourceName   the resource name snapshot at publish time
     * @param actorUserId    the acting user id, or {@code null}
     * @param ownerUserId    the resource owner user id, or {@code null}
     */
    public ResourceLifecycleEvent(
            Integer organizationId,
            ResourceKind kind,
            LifecycleAction action,
            Integer resourceId,
            String resourceName,
            Integer actorUserId,
            Integer ownerUserId
    ) {
        this(organizationId, kind, action, resourceId, resourceName, actorUserId, ownerUserId,
                NotificationSuppressionContext.isSuppressed());
    }
}
