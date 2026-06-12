package com.stocka.backend.modules.ports.entity;

import java.time.LocalDateTime;
import java.util.Date;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.annotations.UpdateTimestamp;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.stocka.backend.modules.organizations.entity.Organization;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * A hardware port declared by an {@link Organization}, for example "Salida tira led 1" wired to the
 * Raspberry Pi 4 GPIO {@link #pin} 21 and related to an existing piece type via {@link #pieceTypeId}
 * (e.g. the "Tira Led" type). Each port carries a list of typed parameters serialized in the
 * {@link #parametersJson} blob; the parameter types reuse {@code AttributeType} and their rules
 * reuse {@code AttributeValidatorsDto}, exactly like a piece-type action does.
 *
 * <p>{@code pieceTypeId} is mapped as a plain column (not a {@code @ManyToOne}) on purpose: piece
 * types carry {@code @SQLRestriction("deleted_at IS NULL")}, so an eager association to a piece type
 * that gets soft-deleted while a port still references it would break hydration when listing ports.
 * The reference is validated to point at an existing piece type of the same organization on write,
 * and a DB-level foreign key keeps it consistent; the display name is resolved separately.
 *
 * <p>This is a private, organization-gated feature (only organizations whose owner is a global admin
 * expose it) and is definitions only: ports describe the available outputs and their parameters;
 * there is no runtime hardware execution.
 *
 * <p>{@link #pin} is intentionally nullable: on soft-delete it is set to {@code null} so the
 * {@code uk_port_org_pin} UNIQUE slot is freed (InnoDB treats multiple NULLs as distinct), mirroring
 * how {@link #name} is mangled with a "::deleted::ID" suffix to free the {@code uk_port_org_name}
 * slot. Active rows always carry a non-null pin (enforced by the service).
 */
@Entity
@Table(
        name = "ports",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_port_org_name",
                        columnNames = {"organization_id", "name"}
                ),
                @UniqueConstraint(
                        name = "uk_port_org_pin",
                        columnNames = {"organization_id", "pin"}
                )
        }
)
@SQLRestriction("deleted_at IS NULL")
public class Port {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Integer id;

    @ManyToOne
    @JoinColumn(name = "organization_id", referencedColumnName = "id", nullable = false)
    private Organization organization;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(name = "piece_type_id", nullable = false)
    private Integer pieceTypeId;

    @Column
    private Integer pin;

    @Column(nullable = false,
            columnDefinition = "INT NOT NULL DEFAULT 0")
    private int position = 0;

    @Lob
    @Column(name = "parameters_json", columnDefinition = "TEXT")
    private String parametersJson;

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

    public Port setId(Integer id) {
        this.id = id;
        return this;
    }

    public Organization getOrganization() {
        return organization;
    }

    public Port setOrganization(Organization organization) {
        this.organization = organization;
        return this;
    }

    public String getName() {
        return name;
    }

    public Port setName(String name) {
        this.name = name;
        return this;
    }

    public Integer getPieceTypeId() {
        return pieceTypeId;
    }

    public Port setPieceTypeId(Integer pieceTypeId) {
        this.pieceTypeId = pieceTypeId;
        return this;
    }

    public Integer getPin() {
        return pin;
    }

    public Port setPin(Integer pin) {
        this.pin = pin;
        return this;
    }

    public int getPosition() {
        return position;
    }

    public Port setPosition(int position) {
        this.position = position;
        return this;
    }

    public String getParametersJson() {
        return parametersJson;
    }

    public Port setParametersJson(String parametersJson) {
        this.parametersJson = parametersJson;
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

    public Port setDeletedAt(LocalDateTime deletedAt) {
        this.deletedAt = deletedAt;
        return this;
    }
}
