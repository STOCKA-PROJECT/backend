package com.stocka.backend.modules.devices.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * One uploaded piece of a player's log file: bytes {@code [startOffset, endOffset)} of
 * {@link #fileName}, stored gzipped in R2 under {@link #storageKey}. Pieces of a file never overlap
 * and, read in {@code startOffset} order, rebuild the file as the player wrote it.
 *
 * <p>The device is mapped as a plain column (not a {@code @ManyToOne}) because {@link Device}
 * carries {@code @SQLRestriction("deleted_at IS NULL")}: the log of a soft-deleted device stays
 * until the retention cleanup removes it. {@code uploadedBy} is the user whose app carried the
 * piece, {@code null} when the player uploaded it itself.
 */
@Entity
@Table(
        name = "device_log_chunks",
        uniqueConstraints = @UniqueConstraint(name = "uk_device_log_chunk_start", columnNames = {"device_id", "file_name", "start_offset"}),
        indexes = @Index(name = "idx_device_log_chunks_day", columnList = "log_day")
)
public class DeviceLogChunk {

    /** Who uploaded a piece. */
    public enum Source {
        /** The player itself, with its heartbeat token. */
        DEVICE,
        /** The belén app of a user, carrying what it read from the player on the local network. */
        USER
    }

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    @Column(name = "device_id", nullable = false)
    private Integer deviceRowId;

    @Column(name = "file_name", nullable = false, length = 80)
    private String fileName;

    @Column(name = "log_day")
    private LocalDate logDay;

    @Column(name = "start_offset", nullable = false)
    private long startOffset;

    @Column(name = "end_offset", nullable = false)
    private long endOffset;

    @Column(name = "line_count", nullable = false)
    private int lineCount;

    @Column(name = "first_at")
    private LocalDateTime firstAt;

    @Column(name = "last_at")
    private LocalDateTime lastAt;

    @Column(name = "storage_key", nullable = false, length = 255)
    private String storageKey;

    @Column(name = "stored_bytes", nullable = false)
    private int storedBytes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Source source;

    @Column(name = "uploaded_by")
    private Integer uploadedBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public Integer getDeviceRowId() {
        return deviceRowId;
    }

    public DeviceLogChunk setDeviceRowId(Integer deviceRowId) {
        this.deviceRowId = deviceRowId;
        return this;
    }

    public String getFileName() {
        return fileName;
    }

    public DeviceLogChunk setFileName(String fileName) {
        this.fileName = fileName;
        return this;
    }

    public LocalDate getLogDay() {
        return logDay;
    }

    public DeviceLogChunk setLogDay(LocalDate logDay) {
        this.logDay = logDay;
        return this;
    }

    public long getStartOffset() {
        return startOffset;
    }

    public DeviceLogChunk setStartOffset(long startOffset) {
        this.startOffset = startOffset;
        return this;
    }

    public long getEndOffset() {
        return endOffset;
    }

    public DeviceLogChunk setEndOffset(long endOffset) {
        this.endOffset = endOffset;
        return this;
    }

    public int getLineCount() {
        return lineCount;
    }

    public DeviceLogChunk setLineCount(int lineCount) {
        this.lineCount = lineCount;
        return this;
    }

    public LocalDateTime getFirstAt() {
        return firstAt;
    }

    public DeviceLogChunk setFirstAt(LocalDateTime firstAt) {
        this.firstAt = firstAt;
        return this;
    }

    public LocalDateTime getLastAt() {
        return lastAt;
    }

    public DeviceLogChunk setLastAt(LocalDateTime lastAt) {
        this.lastAt = lastAt;
        return this;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public DeviceLogChunk setStorageKey(String storageKey) {
        this.storageKey = storageKey;
        return this;
    }

    public int getStoredBytes() {
        return storedBytes;
    }

    public DeviceLogChunk setStoredBytes(int storedBytes) {
        this.storedBytes = storedBytes;
        return this;
    }

    public Source getSource() {
        return source;
    }

    public DeviceLogChunk setSource(Source source) {
        this.source = source;
        return this;
    }

    public Integer getUploadedBy() {
        return uploadedBy;
    }

    public DeviceLogChunk setUploadedBy(Integer uploadedBy) {
        this.uploadedBy = uploadedBy;
        return this;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
