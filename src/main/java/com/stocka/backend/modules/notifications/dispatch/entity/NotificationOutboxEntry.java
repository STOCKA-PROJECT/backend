package com.stocka.backend.modules.notifications.dispatch.entity;

import java.time.LocalDateTime;

import com.stocka.backend.modules.notifications.events.ResourceKind;
import com.stocka.backend.modules.notifications.preferences.entity.LifecycleAction;
import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.users.entity.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * One lifecycle event waiting to be delivered to one recipient inside a digest. Written by
 * {@link com.stocka.backend.modules.notifications.dispatch.PendingResourceEventDispatcher} for
 * every member whose delivery mode is not {@code INSTANT}, read and marked as sent by
 * {@link com.stocka.backend.modules.notifications.dispatch.NotificationDigestDispatcher}.
 *
 * <p>The actor name and the resource URL are snapshots taken when the event was reduced, so the
 * digest never touches the domain again and still shows what the recipient would have seen in an
 * instant email.
 *
 * @since 2026-10-29
 */
@Entity
@Table(
        name = "notification_outbox",
        indexes = @Index(name = "idx_notif_outbox_pending",
                columnList = "sent_at, user_id, organization_id, event_at")
)
public class NotificationOutboxEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Integer id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", referencedColumnName = "id", nullable = false)
    private User user;

    @ManyToOne(optional = false)
    @JoinColumn(name = "organization_id", referencedColumnName = "id", nullable = false)
    private Organization organization;

    @Enumerated(EnumType.STRING)
    @Column(name = "resource_kind", nullable = false, length = 32)
    private ResourceKind resourceKind;

    @Column(name = "resource_id", nullable = false)
    private Integer resourceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 16)
    private LifecycleAction action;

    @Column(name = "resource_name", length = 255)
    private String resourceName;

    @Column(name = "actor_name", length = 255)
    private String actorName;

    @Column(name = "resource_url", nullable = false, length = 512)
    private String resourceUrl;

    @Column(name = "event_at", nullable = false)
    private LocalDateTime eventAt;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    public Integer getId() { return id; }
    public NotificationOutboxEntry setId(Integer id) { this.id = id; return this; }

    public User getUser() { return user; }
    public NotificationOutboxEntry setUser(User user) { this.user = user; return this; }

    public Organization getOrganization() { return organization; }
    public NotificationOutboxEntry setOrganization(Organization organization) {
        this.organization = organization;
        return this;
    }

    public ResourceKind getResourceKind() { return resourceKind; }
    public NotificationOutboxEntry setResourceKind(ResourceKind resourceKind) {
        this.resourceKind = resourceKind;
        return this;
    }

    public Integer getResourceId() { return resourceId; }
    public NotificationOutboxEntry setResourceId(Integer resourceId) {
        this.resourceId = resourceId;
        return this;
    }

    public LifecycleAction getAction() { return action; }
    public NotificationOutboxEntry setAction(LifecycleAction action) { this.action = action; return this; }

    public String getResourceName() { return resourceName; }
    public NotificationOutboxEntry setResourceName(String resourceName) {
        this.resourceName = resourceName;
        return this;
    }

    public String getActorName() { return actorName; }
    public NotificationOutboxEntry setActorName(String actorName) { this.actorName = actorName; return this; }

    public String getResourceUrl() { return resourceUrl; }
    public NotificationOutboxEntry setResourceUrl(String resourceUrl) {
        this.resourceUrl = resourceUrl;
        return this;
    }

    public LocalDateTime getEventAt() { return eventAt; }
    public NotificationOutboxEntry setEventAt(LocalDateTime eventAt) { this.eventAt = eventAt; return this; }

    public LocalDateTime getSentAt() { return sentAt; }
    public NotificationOutboxEntry setSentAt(LocalDateTime sentAt) { this.sentAt = sentAt; return this; }
}
