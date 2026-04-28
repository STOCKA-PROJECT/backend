package com.stocka.backend.modules.pieces.entity;

import java.util.Date;

import org.hibernate.annotations.CreationTimestamp;

import com.stocka.backend.modules.users.entity.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Append-only audit entry describing one mutation on a piece. Kept separate from
 * {@code organization_audit_logs} because the cardinality and access patterns are very different
 * (one row per attribute change, queried by piece).
 */
@Entity
@Table(
        name = "piece_history",
        indexes = {
                @Index(name = "idx_piece_history_piece_created", columnList = "piece_id, created_at")
        }
)
public class PieceHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "piece_id", referencedColumnName = "id", nullable = false)
    private Piece piece;

    @ManyToOne
    @JoinColumn(name = "actor_user_id", referencedColumnName = "id")
    private User actor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private PieceHistoryAction action;

    @Column(name = "field_name", length = 80)
    private String fieldName;

    @Lob
    @Column(name = "old_value", columnDefinition = "TEXT")
    private String oldValue;

    @Lob
    @Column(name = "new_value", columnDefinition = "TEXT")
    private String newValue;

    @CreationTimestamp
    @Column(updatable = false, name = "created_at")
    private Date createdAt;

    public Long getId() { return id; }
    public PieceHistory setId(Long id) { this.id = id; return this; }

    public Piece getPiece() { return piece; }
    public PieceHistory setPiece(Piece piece) { this.piece = piece; return this; }

    public User getActor() { return actor; }
    public PieceHistory setActor(User actor) { this.actor = actor; return this; }

    public PieceHistoryAction getAction() { return action; }
    public PieceHistory setAction(PieceHistoryAction action) { this.action = action; return this; }

    public String getFieldName() { return fieldName; }
    public PieceHistory setFieldName(String fieldName) { this.fieldName = fieldName; return this; }

    public String getOldValue() { return oldValue; }
    public PieceHistory setOldValue(String oldValue) { this.oldValue = oldValue; return this; }

    public String getNewValue() { return newValue; }
    public PieceHistory setNewValue(String newValue) { this.newValue = newValue; return this; }

    public Date getCreatedAt() { return createdAt; }
}
