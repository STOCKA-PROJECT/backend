package com.stocka.backend.modules.locations.entity;

import java.time.LocalDateTime;
import java.util.Date;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.annotations.UpdateTimestamp;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.sync.support.SyncableEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * Hierarchical location inside an organization. A location can contain sub-locations and pieces.
 * The parent reference is nullable: a {@code null} parent means the location is at the root of
 * the org's tree.
 */
@Entity
@Table(
        name = "locations",
        indexes = {
                @Index(name = "idx_location_org_parent", columnList = "organization_id, parent_id"),
                @Index(name = "idx_location_org_rev", columnList = "organization_id, rev"),
                @Index(name = "uk_location_sync_id", columnList = "sync_id", unique = true)
        }
)
@SQLRestriction("deleted_at IS NULL")
public class Location implements SyncableEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Integer id;

    /** Client-stable synchronization id (UUID); identity used by offline clients. */
    @Column(name = "sync_id", length = 36, unique = true)
    private String syncId;

    /** Per-organization change-sequence value stamped on every write; the offline pull cursor. */
    @Column(name = "rev")
    private Long rev;

    @ManyToOne
    @JoinColumn(name = "organization_id", referencedColumnName = "id", nullable = false)
    private Organization organization;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @ManyToOne
    @JoinColumn(name = "parent_id", referencedColumnName = "id")
    private Location parent;

    @CreationTimestamp
    @Column(updatable = false, name = "created_at")
    private Date createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Date updatedAt;

    @JsonIgnore
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public Integer getId() {
        return id;
    }

    public Location setId(Integer id) {
        this.id = id;
        return this;
    }

    public Organization getOrganization() {
        return organization;
    }

    public Location setOrganization(Organization organization) {
        this.organization = organization;
        return this;
    }

    public String getName() {
        return name;
    }

    public Location setName(String name) {
        this.name = name;
        return this;
    }

    public String getDescription() {
        return description;
    }

    public Location setDescription(String description) {
        this.description = description;
        return this;
    }

    public Location getParent() {
        return parent;
    }

    public Location setParent(Location parent) {
        this.parent = parent;
        return this;
    }

    public Date getCreatedAt() {
        return createdAt;
    }

    public Date getUpdatedAt() {
        return updatedAt;
    }

    public LocalDateTime getDeletedAt() {
        return deletedAt;
    }

    public Location setDeletedAt(LocalDateTime deletedAt) {
        this.deletedAt = deletedAt;
        return this;
    }

    /** Defensively assigns a {@code syncId} on insert when none was provided. */
    @PrePersist
    private void assignSyncIdIfMissing() {
        if (syncId == null) {
            syncId = java.util.UUID.randomUUID().toString();
        }
    }

    @Override
    public String getSyncId() {
        return syncId;
    }

    @Override
    public void setSyncId(String syncId) {
        this.syncId = syncId;
    }

    @Override
    public Long getRev() {
        return rev;
    }

    @Override
    public void setRev(Long rev) {
        this.rev = rev;
    }

    @Override
    public Integer getSyncOrganizationId() {
        return organization == null ? null : organization.getId();
    }
}
