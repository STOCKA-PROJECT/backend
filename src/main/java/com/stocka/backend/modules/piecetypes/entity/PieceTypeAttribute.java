package com.stocka.backend.modules.piecetypes.entity;

import java.time.LocalDateTime;
import java.util.Date;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.annotations.UpdateTimestamp;

import com.fasterxml.jackson.annotation.JsonIgnore;

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
 * Single attribute (field) declared by a {@link PieceType}. The {@link #validatorsJson} blob
 * stores a JSON object with type-specific rules (min/max/regex/options/...) that are interpreted
 * by the validator strategy matching {@link #type}.
 */
@Entity
@Table(
        name = "piece_type_attributes",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_piece_type_attr_type_name",
                columnNames = {"piece_type_id", "name"}
        )
)
@SQLRestriction("deleted_at IS NULL")
public class PieceTypeAttribute {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Integer id;

    @ManyToOne
    @JoinColumn(name = "piece_type_id", referencedColumnName = "id", nullable = false)
    private PieceType pieceType;

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

    public Integer getId() {
        return id;
    }

    public PieceTypeAttribute setId(Integer id) {
        this.id = id;
        return this;
    }

    public PieceType getPieceType() {
        return pieceType;
    }

    public PieceTypeAttribute setPieceType(PieceType pieceType) {
        this.pieceType = pieceType;
        return this;
    }

    public String getName() {
        return name;
    }

    public PieceTypeAttribute setName(String name) {
        this.name = name;
        return this;
    }

    public String getDisplayName() {
        return displayName;
    }

    public PieceTypeAttribute setDisplayName(String displayName) {
        this.displayName = displayName;
        return this;
    }

    public AttributeType getType() {
        return type;
    }

    public PieceTypeAttribute setType(AttributeType type) {
        this.type = type;
        return this;
    }

    public boolean isRequired() {
        return required;
    }

    public PieceTypeAttribute setRequired(boolean required) {
        this.required = required;
        return this;
    }

    public int getPosition() {
        return position;
    }

    public PieceTypeAttribute setPosition(int position) {
        this.position = position;
        return this;
    }

    public String getValidatorsJson() {
        return validatorsJson;
    }

    public PieceTypeAttribute setValidatorsJson(String validatorsJson) {
        this.validatorsJson = validatorsJson;
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

    public PieceTypeAttribute setDeletedAt(LocalDateTime deletedAt) {
        this.deletedAt = deletedAt;
        return this;
    }
}
