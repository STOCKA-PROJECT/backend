package com.stocka.backend.modules.pieces.entity;

import com.stocka.backend.modules.organizations.entity.OrganizationPieceAttribute;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Persisted value for one organization-level attribute of one piece. Mirrors
 * {@link PieceAttributeValue} but ties values to {@link OrganizationPieceAttribute} instead of a
 * type-level attribute. Kept on a separate table to avoid mixing two foreign-key spaces in the
 * same row and to keep the type-level repository's queries simple.
 *
 * <p>The {@link #value} string holds the canonical representation produced by the validator
 * matching the attribute's type (same normalization rules as {@link PieceAttributeValue}).
 */
@Entity
@Table(
        name = "piece_organization_attribute_values",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_piece_org_attr_value_piece_attr",
                columnNames = {"piece_id", "organization_attribute_id"}
        )
)
public class PieceOrganizationAttributeValue {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Integer id;

    @ManyToOne
    @JoinColumn(name = "piece_id", referencedColumnName = "id", nullable = false)
    private Piece piece;

    @ManyToOne
    @JoinColumn(name = "organization_attribute_id", referencedColumnName = "id", nullable = false)
    private OrganizationPieceAttribute attribute;

    @Column(name = "attribute_value", columnDefinition = "TEXT")
    private String value;

    public Integer getId() { return id; }
    public PieceOrganizationAttributeValue setId(Integer id) { this.id = id; return this; }

    public Piece getPiece() { return piece; }
    public PieceOrganizationAttributeValue setPiece(Piece piece) { this.piece = piece; return this; }

    public OrganizationPieceAttribute getAttribute() { return attribute; }
    public PieceOrganizationAttributeValue setAttribute(OrganizationPieceAttribute attribute) {
        this.attribute = attribute;
        return this;
    }

    public String getValue() { return value; }
    public PieceOrganizationAttributeValue setValue(String value) { this.value = value; return this; }
}
