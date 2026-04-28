package com.stocka.backend.modules.pieces.entity;

import com.stocka.backend.modules.piecetypes.entity.PieceTypeAttribute;

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
 * Persisted value for one attribute of one piece. The {@link #value} string holds the canonical
 * representation produced by the matching
 * {@code AttributeValueValidator} (e.g. ISO date, JSON array, decimal as plain string).
 */
@Entity
@Table(
        name = "piece_attribute_values",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_piece_attr_value_piece_attr",
                columnNames = {"piece_id", "piece_type_attribute_id"}
        )
)
public class PieceAttributeValue {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Integer id;

    @ManyToOne
    @JoinColumn(name = "piece_id", referencedColumnName = "id", nullable = false)
    private Piece piece;

    @ManyToOne
    @JoinColumn(name = "piece_type_attribute_id", referencedColumnName = "id", nullable = false)
    private PieceTypeAttribute attribute;

    @Column(name = "attribute_value", columnDefinition = "TEXT")
    private String value;

    public Integer getId() { return id; }
    public PieceAttributeValue setId(Integer id) { this.id = id; return this; }

    public Piece getPiece() { return piece; }
    public PieceAttributeValue setPiece(Piece piece) { this.piece = piece; return this; }

    public PieceTypeAttribute getAttribute() { return attribute; }
    public PieceAttributeValue setAttribute(PieceTypeAttribute attribute) { this.attribute = attribute; return this; }

    public String getValue() { return value; }
    public PieceAttributeValue setValue(String value) { this.value = value; return this; }
}
