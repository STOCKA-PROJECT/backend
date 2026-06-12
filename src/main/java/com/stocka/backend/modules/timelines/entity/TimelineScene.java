package com.stocka.backend.modules.timelines.entity;

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
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * The editor document for a {@link Timeline}: the board configuration (dimensions + shape), its
 * layers, the placed articles, and the video-editor-style tracks/clips. Stored as a single
 * versioned JSON blob ({@link #document}) owned by the front-end editor; the back-end only validates
 * structural integrity and cross-organization references.
 *
 * <p>One scene per timeline ({@code uk_timeline_scene_timeline}). {@link #version} backs optimistic
 * concurrency for autosave.
 */
@Entity
@Table(
        name = "timeline_scenes",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_timeline_scene_timeline",
                columnNames = {"timeline_id"}
        )
)
@SQLRestriction("deleted_at IS NULL")
public class TimelineScene {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Integer id;

    @OneToOne
    @JoinColumn(name = "timeline_id", referencedColumnName = "id", nullable = false)
    private Timeline timeline;

    @Column(name = "schema_version", nullable = false)
    private int schemaVersion = 1;

    @Lob
    @Column(name = "document", columnDefinition = "LONGTEXT")
    private String document;

    /** Monotonic revision used for optimistic concurrency on autosave. */
    @Column(nullable = false)
    private int version = 1;

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

    public TimelineScene setId(Integer id) {
        this.id = id;
        return this;
    }

    public Timeline getTimeline() {
        return timeline;
    }

    public TimelineScene setTimeline(Timeline timeline) {
        this.timeline = timeline;
        return this;
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public TimelineScene setSchemaVersion(int schemaVersion) {
        this.schemaVersion = schemaVersion;
        return this;
    }

    public String getDocument() {
        return document;
    }

    public TimelineScene setDocument(String document) {
        this.document = document;
        return this;
    }

    public int getVersion() {
        return version;
    }

    public TimelineScene setVersion(int version) {
        this.version = version;
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

    public TimelineScene setDeletedAt(LocalDateTime deletedAt) {
        this.deletedAt = deletedAt;
        return this;
    }
}
