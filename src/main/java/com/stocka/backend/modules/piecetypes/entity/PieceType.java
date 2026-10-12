package com.stocka.backend.modules.piecetypes.entity;

import java.time.LocalDateTime;
import java.util.Date;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.annotations.UpdateTimestamp;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.stocka.backend.modules.contract.OutputKind;
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
 * Reusable category of pieces inside an organization. Defines a schema of attributes (see
 * {@link PieceTypeAttribute}) that every piece of this type must respect.
 *
 * <p>{@link #kind} is optional: when set, the type drives hardware of that {@link OutputKind}, its
 * actions must come from the output catalog for that kind, and every port created for the type must
 * be of the same kind. A type without kind is inventory-only.
 */
@Entity
@Table(
        name = "piece_types",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_piece_type_org_name",
                columnNames = {"organization_id", "name"}
        )
)
@SQLRestriction("deleted_at IS NULL")
public class PieceType {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Integer id;

    @ManyToOne
    @JoinColumn(name = "organization_id", referencedColumnName = "id", nullable = false)
    private Organization organization;

    @Column(nullable = false, length = 120)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private OutputKind kind;

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

    public PieceType setId(Integer id) {
        this.id = id;
        return this;
    }

    public Organization getOrganization() {
        return organization;
    }

    public PieceType setOrganization(Organization organization) {
        this.organization = organization;
        return this;
    }

    public String getName() {
        return name;
    }

    public PieceType setName(String name) {
        this.name = name;
        return this;
    }

    public OutputKind getKind() {
        return kind;
    }

    public PieceType setKind(OutputKind kind) {
        this.kind = kind;
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

    public PieceType setDeletedAt(LocalDateTime deletedAt) {
        this.deletedAt = deletedAt;
        return this;
    }
}
