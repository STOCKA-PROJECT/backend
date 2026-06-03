package com.stocka.backend.modules.organizations.entity;

import java.time.LocalDateTime;
import java.util.Date;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.annotations.UpdateTimestamp;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.stocka.backend.modules.piecetypes.entity.AttributeType;
import com.stocka.backend.modules.sync.support.SyncableBaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Organization-level piece attribute. Structurally identical to a
 * {@link com.stocka.backend.modules.piecetypes.entity.PieceTypeAttribute} but scoped to all
 * pieces of an {@link Organization} instead of a specific
 * {@link com.stocka.backend.modules.piecetypes.entity.PieceType}. Used to add common fields
 * (e.g. warranty date, supplier) that every piece in the organization must carry regardless of
 * its type.
 */
@Entity
@Table(
        name = "organization_piece_attributes",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_org_piece_attr_org_name",
                columnNames = {"organization_id", "name"}
        )
)
@SQLRestriction("deleted_at IS NULL")
public class OrganizationPieceAttribute extends SyncableBaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Integer id;

    @ManyToOne
    @JoinColumn(name = "organization_id", referencedColumnName = "id", nullable = false)
    private Organization organization;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(name = "display_name", nullable = false, length = 160)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AttributeType type;

    @Column(name = "is_required", nullable = false,
            columnDefinition = "BOOLEAN NOT NULL DEFAULT TRUE")
    private boolean required = true;

    @Column(nullable = false,
            columnDefinition = "INT NOT NULL DEFAULT 0")
    private int position = 0;

    @Lob
    @Column(name = "validators_json", columnDefinition = "TEXT")
    private String validatorsJson;

    @CreationTimestamp
    @Column(updatable = false, name = "created_at")
    private Date createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Date updatedAt;

    @JsonIgnore
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public Integer getId() { return id; }
    public OrganizationPieceAttribute setId(Integer id) { this.id = id; return this; }

    public Organization getOrganization() { return organization; }
    public OrganizationPieceAttribute setOrganization(Organization organization) {
        this.organization = organization;
        return this;
    }

    public String getName() { return name; }
    public OrganizationPieceAttribute setName(String name) { this.name = name; return this; }

    public String getDisplayName() { return displayName; }
    public OrganizationPieceAttribute setDisplayName(String displayName) {
        this.displayName = displayName;
        return this;
    }

    public AttributeType getType() { return type; }
    public OrganizationPieceAttribute setType(AttributeType type) { this.type = type; return this; }

    public boolean isRequired() { return required; }
    public OrganizationPieceAttribute setRequired(boolean required) { this.required = required; return this; }

    public int getPosition() { return position; }
    public OrganizationPieceAttribute setPosition(int position) { this.position = position; return this; }

    public String getValidatorsJson() { return validatorsJson; }
    public OrganizationPieceAttribute setValidatorsJson(String validatorsJson) {
        this.validatorsJson = validatorsJson;
        return this;
    }

    public Date getCreatedAt() { return createdAt; }
    public Date getUpdatedAt() { return updatedAt; }

    public LocalDateTime getDeletedAt() { return deletedAt; }
    public OrganizationPieceAttribute setDeletedAt(LocalDateTime deletedAt) {
        this.deletedAt = deletedAt;
        return this;
    }

    @Override
    public Integer getSyncOrganizationId() {
        return organization == null ? null : organization.getId();
    }
}
