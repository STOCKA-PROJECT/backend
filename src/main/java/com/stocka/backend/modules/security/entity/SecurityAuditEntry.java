package com.stocka.backend.modules.security.entity;

import java.time.LocalDateTime;

import com.stocka.backend.modules.security.audit.SecurityEventType;
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
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Append-only record of a security-relevant action. Written asynchronously by
 * {@link com.stocka.backend.modules.security.audit.SecurityAuditListener}; the
 * row's {@code created_at} therefore lags the wall-clock time of the action by
 * a few milliseconds.
 *
 * <p>{@code user_id} is nullable because some events (failed login from an
 * unknown email, password-reset for a non-registered address) can't be tied
 * to a user. {@code email} carries a snapshot regardless so the panel can
 * still show "you tried to log in as X".
 */
@Entity
@Table(name = "security_audit_entries", indexes = {
        @Index(name = "idx_security_audit_user_created", columnList = "user_id, created_at DESC"),
        @Index(name = "idx_security_audit_event_created", columnList = "event_type, created_at DESC"),
        @Index(name = "idx_security_audit_created", columnList = "created_at")
})
public class SecurityAuditEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "user_id", referencedColumnName = "id")
    private User user;

    @Column(name = "email", length = 255)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 64)
    private SecurityEventType eventType;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    /** Free-form JSON with event-specific extras (e.g. provider="GOOGLE", family_id="..."). */
    @Column(name = "metadata", columnDefinition = "TEXT")
    private String metadata;

    @Column(name = "success", nullable = false)
    private boolean success;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public SecurityAuditEntry setUser(User user) {
        this.user = user;
        return this;
    }

    public String getEmail() {
        return email;
    }

    public SecurityAuditEntry setEmail(String email) {
        this.email = email;
        return this;
    }

    public SecurityEventType getEventType() {
        return eventType;
    }

    public SecurityAuditEntry setEventType(SecurityEventType eventType) {
        this.eventType = eventType;
        return this;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public SecurityAuditEntry setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
        return this;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public SecurityAuditEntry setUserAgent(String userAgent) {
        this.userAgent = userAgent;
        return this;
    }

    public String getMetadata() {
        return metadata;
    }

    public SecurityAuditEntry setMetadata(String metadata) {
        this.metadata = metadata;
        return this;
    }

    public boolean isSuccess() {
        return success;
    }

    public SecurityAuditEntry setSuccess(boolean success) {
        this.success = success;
        return this;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public SecurityAuditEntry setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
        return this;
    }
}
