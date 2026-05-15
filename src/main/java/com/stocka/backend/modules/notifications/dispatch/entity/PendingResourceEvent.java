package com.stocka.backend.modules.notifications.dispatch.entity;

import java.time.LocalDateTime;

import com.stocka.backend.modules.notifications.events.ResourceKind;
import com.stocka.backend.modules.notifications.preferences.entity.LifecycleAction;
import com.stocka.backend.modules.organizations.entity.Organization;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Coalesced view of one or more lifecycle events about the same resource. Holds the
 * first and last action seen so the flusher can decide the effective action; the
 * timestamps drive the quiet-window check. Owner and actor snapshots come from the
 * most recent event so the resulting email shows up-to-date attribution.
 *
 * <p>This row is never soft-deleted: when the flusher dispatches the email (or decides
 * to drop the transient case) it removes the row outright. {@code attempts} grows when
 * a flush attempt fails; once it exceeds {@code app.notifications.max-attempts} the row
 * is force-deleted to keep the queue moving.
 */
@Entity
@Table(
        name = "pending_resource_events",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_pending_org_kind_res",
                columnNames = {"organization_id", "resource_kind", "resource_id"}
        )
)
public class PendingResourceEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Integer id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "organization_id", referencedColumnName = "id", nullable = false)
    private Organization organization;

    @Enumerated(EnumType.STRING)
    @Column(name = "resource_kind", nullable = false, length = 32)
    private ResourceKind resourceKind;

    @Column(name = "resource_id", nullable = false)
    private Integer resourceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "first_action", nullable = false, length = 16)
    private LifecycleAction firstAction;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_action", nullable = false, length = 16)
    private LifecycleAction lastAction;

    @Column(name = "first_event_at", nullable = false)
    private LocalDateTime firstEventAt;

    @Column(name = "last_event_at", nullable = false)
    private LocalDateTime lastEventAt;

    @Column(name = "actor_user_id")
    private Integer actorUserId;

    @Column(name = "resource_name", length = 255)
    private String resourceName;

    @Column(name = "owner_user_id")
    private Integer ownerUserId;

    @Column(name = "attempts", nullable = false)
    private int attempts = 0;

    public Integer getId() { return id; }
    public PendingResourceEvent setId(Integer id) { this.id = id; return this; }

    public Organization getOrganization() { return organization; }
    public PendingResourceEvent setOrganization(Organization organization) {
        this.organization = organization;
        return this;
    }

    public ResourceKind getResourceKind() { return resourceKind; }
    public PendingResourceEvent setResourceKind(ResourceKind resourceKind) {
        this.resourceKind = resourceKind;
        return this;
    }

    public Integer getResourceId() { return resourceId; }
    public PendingResourceEvent setResourceId(Integer resourceId) {
        this.resourceId = resourceId;
        return this;
    }

    public LifecycleAction getFirstAction() { return firstAction; }
    public PendingResourceEvent setFirstAction(LifecycleAction firstAction) {
        this.firstAction = firstAction;
        return this;
    }

    public LifecycleAction getLastAction() { return lastAction; }
    public PendingResourceEvent setLastAction(LifecycleAction lastAction) {
        this.lastAction = lastAction;
        return this;
    }

    public LocalDateTime getFirstEventAt() { return firstEventAt; }
    public PendingResourceEvent setFirstEventAt(LocalDateTime firstEventAt) {
        this.firstEventAt = firstEventAt;
        return this;
    }

    public LocalDateTime getLastEventAt() { return lastEventAt; }
    public PendingResourceEvent setLastEventAt(LocalDateTime lastEventAt) {
        this.lastEventAt = lastEventAt;
        return this;
    }

    public Integer getActorUserId() { return actorUserId; }
    public PendingResourceEvent setActorUserId(Integer actorUserId) {
        this.actorUserId = actorUserId;
        return this;
    }

    public String getResourceName() { return resourceName; }
    public PendingResourceEvent setResourceName(String resourceName) {
        this.resourceName = resourceName;
        return this;
    }

    public Integer getOwnerUserId() { return ownerUserId; }
    public PendingResourceEvent setOwnerUserId(Integer ownerUserId) {
        this.ownerUserId = ownerUserId;
        return this;
    }

    public int getAttempts() { return attempts; }
    public PendingResourceEvent setAttempts(int attempts) {
        this.attempts = attempts;
        return this;
    }
}
