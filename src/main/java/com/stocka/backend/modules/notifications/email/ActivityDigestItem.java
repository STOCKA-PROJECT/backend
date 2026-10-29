package com.stocka.backend.modules.notifications.email;

import java.time.LocalDateTime;

import com.stocka.backend.modules.notifications.events.ResourceKind;
import com.stocka.backend.modules.notifications.preferences.entity.LifecycleAction;

/**
 * One line of an activity digest: what happened to which resource, by whom and when.
 *
 * @param kind         resource kind
 * @param action       effective lifecycle action
 * @param resourceName latest snapshot of the resource name
 * @param resourceUrl  absolute URL of the resource (ignored for {@code DELETED}, which has no detail)
 * @param actorName    display name of the actor, never {@code null}
 * @param eventAt      when the event was observed, already converted to the zone the digest prints
 * @since 2026-10-29
 */
public record ActivityDigestItem(
        ResourceKind kind,
        LifecycleAction action,
        String resourceName,
        String resourceUrl,
        String actorName,
        LocalDateTime eventAt
) {
}
