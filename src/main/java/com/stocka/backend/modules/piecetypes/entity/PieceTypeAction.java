package com.stocka.backend.modules.piecetypes.entity;

import java.time.LocalDateTime;
import java.util.Date;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.annotations.UpdateTimestamp;

import com.fasterxml.jackson.annotation.JsonIgnore;

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
 * A callable action (function) declared by a {@link PieceType}, for example {@code encender} on a
 * "Pieza Movimiento" type. Each action carries a list of typed parameters serialized in the
 * {@link #parametersJson} blob; the parameter types reuse {@link AttributeType} and their rules
 * reuse {@code AttributeValidatorsDto}, exactly like {@link PieceTypeAttribute} does for fields.
 *
 * <p>Definitions only: actions describe the available functions and their parameters; there is no
 * runtime execution engine.
 */
@Entity
@Table(
        name = "piece_type_actions",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_piece_type_action_type_name",
                columnNames = {"piece_type_id", "name"}
        )
)
@SQLRestriction("deleted_at IS NULL")
public class PieceTypeAction {
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

    @Column(length = 255)
    private String description;

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

    public PieceTypeAction setId(Integer id) {
        this.id = id;
        return this;
    }

    public PieceType getPieceType() {
        return pieceType;
    }

    public PieceTypeAction setPieceType(PieceType pieceType) {
        this.pieceType = pieceType;
        return this;
    }

    public String getName() {
        return name;
    }

    public PieceTypeAction setName(String name) {
        this.name = name;
        return this;
    }

    public String getDisplayName() {
        return displayName;
    }

    public PieceTypeAction setDisplayName(String displayName) {
        this.displayName = displayName;
        return this;
    }

    public String getDescription() {
        return description;
    }

    public PieceTypeAction setDescription(String description) {
        this.description = description;
        return this;
    }

    public int getPosition() {
        return position;
    }

    public PieceTypeAction setPosition(int position) {
        this.position = position;
        return this;
    }

    public String getParametersJson() {
        return parametersJson;
    }

    public PieceTypeAction setParametersJson(String parametersJson) {
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

    public PieceTypeAction setDeletedAt(LocalDateTime deletedAt) {
        this.deletedAt = deletedAt;
        return this;
    }
}
