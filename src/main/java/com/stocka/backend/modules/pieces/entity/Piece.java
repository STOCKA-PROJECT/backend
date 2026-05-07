package com.stocka.backend.modules.pieces.entity;

import java.time.LocalDateTime;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Set;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.annotations.UpdateTimestamp;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.stocka.backend.modules.locations.entity.Location;
import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.piecetypes.entity.PieceType;
import com.stocka.backend.modules.users.entity.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * One inventory item ("artículo") inside an organization. Belongs to one or more
 * {@link PieceType}s that together define its dynamic attribute schema. Optional owner,
 * location, serial number (unique within the organization, validated at the service layer)
 * and cover attachment.
 */
@Entity
@Table(
        name = "pieces",
        indexes = {
                @Index(name = "idx_piece_org_status", columnList = "organization_id, status"),
                @Index(name = "idx_piece_org_serial", columnList = "organization_id, serial_number")
        }
)
@SQLRestriction("deleted_at IS NULL")
public class Piece {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Integer id;

    @ManyToOne
    @JoinColumn(name = "organization_id", referencedColumnName = "id", nullable = false)
    private Organization organization;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "piece_piece_types",
            joinColumns = @JoinColumn(name = "piece_id", referencedColumnName = "id"),
            inverseJoinColumns = @JoinColumn(name = "piece_type_id", referencedColumnName = "id"),
            uniqueConstraints = @UniqueConstraint(
                    name = "uk_piece_piece_types_piece_type",
                    columnNames = {"piece_id", "piece_type_id"}
            )
    )
    private Set<PieceType> pieceTypes = new LinkedHashSet<>();

    @Column(nullable = false, length = 255)
    private String name;

    @Column(name = "serial_number", length = 100)
    private String serialNumber;

    @Column(columnDefinition = "TEXT")
    private String description;

    @ManyToOne
    @JoinColumn(name = "owner_user_id", referencedColumnName = "id")
    private User owner;

    @ManyToOne
    @JoinColumn(name = "location_id", referencedColumnName = "id")
    private Location location;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cover_attachment_id", referencedColumnName = "id")
    private PieceAttachment coverAttachment;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10,
            columnDefinition = "VARCHAR(10) NOT NULL DEFAULT 'PENDING'")
    private PieceStatus status = PieceStatus.PENDING;

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
    public Piece setId(Integer id) { this.id = id; return this; }

    public Organization getOrganization() { return organization; }
    public Piece setOrganization(Organization organization) { this.organization = organization; return this; }

    public Set<PieceType> getPieceTypes() { return pieceTypes; }
    public Piece setPieceTypes(Set<PieceType> pieceTypes) {
        this.pieceTypes = pieceTypes == null ? new LinkedHashSet<>() : pieceTypes;
        return this;
    }

    public String getName() { return name; }
    public Piece setName(String name) { this.name = name; return this; }

    public String getSerialNumber() { return serialNumber; }
    public Piece setSerialNumber(String serialNumber) { this.serialNumber = serialNumber; return this; }

    public String getDescription() { return description; }
    public Piece setDescription(String description) { this.description = description; return this; }

    public User getOwner() { return owner; }
    public Piece setOwner(User owner) { this.owner = owner; return this; }

    public Location getLocation() { return location; }
    public Piece setLocation(Location location) { this.location = location; return this; }

    public PieceAttachment getCoverAttachment() { return coverAttachment; }
    public Piece setCoverAttachment(PieceAttachment coverAttachment) {
        this.coverAttachment = coverAttachment;
        return this;
    }

    public PieceStatus getStatus() { return status; }
    public Piece setStatus(PieceStatus status) { this.status = status; return this; }

    public Date getCreatedAt() { return createdAt; }
    public Date getUpdatedAt() { return updatedAt; }

    public LocalDateTime getDeletedAt() { return deletedAt; }
    public Piece setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; return this; }
}
