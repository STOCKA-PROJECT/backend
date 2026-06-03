package com.stocka.backend.modules.pieces.entity;

import java.time.LocalDateTime;
import java.util.Date;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SQLRestriction;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.stocka.backend.modules.sync.support.SyncableBaseEntity;
import com.stocka.backend.modules.users.entity.User;

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

/**
 * Image or document attached to a piece. Binary content lives in R2 (or its local fallback);
 * this entity stores only metadata and the {@code r2Key} pointing to the object.
 */
@Entity
@Table(name = "piece_attachments")
@SQLRestriction("deleted_at IS NULL")
public class PieceAttachment extends SyncableBaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Integer id;

    @ManyToOne
    @JoinColumn(name = "piece_id", referencedColumnName = "id", nullable = false)
    private Piece piece;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PieceAttachmentKind kind;

    @Column(name = "r2_key", nullable = false, length = 512)
    private String r2Key;

    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;

    @Column(name = "mime_type", nullable = false, length = 120)
    private String mimeType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @ManyToOne
    @JoinColumn(name = "uploaded_by_user_id", referencedColumnName = "id")
    private User uploadedBy;

    @CreationTimestamp
    @Column(updatable = false, name = "created_at")
    private Date createdAt;

    @JsonIgnore
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public Integer getId() { return id; }
    public PieceAttachment setId(Integer id) { this.id = id; return this; }

    public Piece getPiece() { return piece; }
    public PieceAttachment setPiece(Piece piece) { this.piece = piece; return this; }

    public PieceAttachmentKind getKind() { return kind; }
    public PieceAttachment setKind(PieceAttachmentKind kind) { this.kind = kind; return this; }

    public String getR2Key() { return r2Key; }
    public PieceAttachment setR2Key(String r2Key) { this.r2Key = r2Key; return this; }

    public String getOriginalFilename() { return originalFilename; }
    public PieceAttachment setOriginalFilename(String originalFilename) { this.originalFilename = originalFilename; return this; }

    public String getMimeType() { return mimeType; }
    public PieceAttachment setMimeType(String mimeType) { this.mimeType = mimeType; return this; }

    public long getSizeBytes() { return sizeBytes; }
    public PieceAttachment setSizeBytes(long sizeBytes) { this.sizeBytes = sizeBytes; return this; }

    public User getUploadedBy() { return uploadedBy; }
    public PieceAttachment setUploadedBy(User uploadedBy) { this.uploadedBy = uploadedBy; return this; }

    public Date getCreatedAt() { return createdAt; }

    public LocalDateTime getDeletedAt() { return deletedAt; }
    public PieceAttachment setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; return this; }

    @Override
    public Integer getSyncOrganizationId() {
        if (piece == null || piece.getOrganization() == null) {
            return null;
        }
        return piece.getOrganization().getId();
    }
}
