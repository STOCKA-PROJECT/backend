package com.stocka.backend.modules.users.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * One "session" the user can see in the panel — backed by exactly one refresh
 * token family. A new {@link UserDevice} row is created on login; rotations
 * within the same family just bump {@code lastSeenAt} and update {@code
 * lastIp}.
 *
 * <p>{@code revokedAt} is set when the user explicitly revokes the session
 * from the panel (or when the family is wiped by reuse detection / password
 * change). The matching {@code refresh_tokens} rows are revoked in lockstep.
 *
 * <p>Using the refresh family as the device identity (instead of a separate
 * fingerprinting cookie) means a fresh login from the same browser shows as
 * a new session. We accept that trade-off to avoid a parallel cookie and the
 * fingerprinting concerns that come with it.
 */
@Entity
@Table(name = "user_devices", indexes = {
        @Index(name = "idx_user_devices_user_revoked", columnList = "user_id, revoked_at"),
        @Index(name = "idx_user_devices_family", columnList = "family_id", unique = true)
})
public class UserDevice {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", referencedColumnName = "id", nullable = false)
    private User user;

    /** Refresh-token {@code family_id} the device is bound to (unique). */
    @Column(name = "family_id", nullable = false, length = 36)
    private String familyId;

    /** Human-friendly label, e.g. "Chrome on macOS". Editable via PATCH. */
    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    /** Original {@code User-Agent} header, kept for forensics. */
    @Column(name = "user_agent_raw", length = 512)
    private String userAgentRaw;

    @Column(name = "last_ip", length = 45)
    private String lastIp;

    @Column(name = "first_seen_at", nullable = false)
    private LocalDateTime firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private LocalDateTime lastSeenAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public UserDevice setUser(User user) {
        this.user = user;
        return this;
    }

    public String getFamilyId() {
        return familyId;
    }

    public UserDevice setFamilyId(String familyId) {
        this.familyId = familyId;
        return this;
    }

    public String getDisplayName() {
        return displayName;
    }

    public UserDevice setDisplayName(String displayName) {
        this.displayName = displayName;
        return this;
    }

    public String getUserAgentRaw() {
        return userAgentRaw;
    }

    public UserDevice setUserAgentRaw(String userAgentRaw) {
        this.userAgentRaw = userAgentRaw;
        return this;
    }

    public String getLastIp() {
        return lastIp;
    }

    public UserDevice setLastIp(String lastIp) {
        this.lastIp = lastIp;
        return this;
    }

    public LocalDateTime getFirstSeenAt() {
        return firstSeenAt;
    }

    public UserDevice setFirstSeenAt(LocalDateTime firstSeenAt) {
        this.firstSeenAt = firstSeenAt;
        return this;
    }

    public LocalDateTime getLastSeenAt() {
        return lastSeenAt;
    }

    public UserDevice setLastSeenAt(LocalDateTime lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
        return this;
    }

    public LocalDateTime getRevokedAt() {
        return revokedAt;
    }

    public UserDevice setRevokedAt(LocalDateTime revokedAt) {
        this.revokedAt = revokedAt;
        return this;
    }
}
