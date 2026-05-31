package com.stocka.backend.modules.auth.entity;

import java.time.LocalDateTime;

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
 * Long-lived refresh token used to mint short-lived access tokens. The raw value
 * never reaches the database: only a SHA-256 hex hash is persisted.
 *
 * <p>Tokens are grouped by {@code familyId}. When a single member of a family is
 * presented twice (reuse), the whole family is revoked under the assumption that
 * an attacker has captured a token. See {@code AUTH_REFRESH_TOKEN_REUSED}.
 */
@Entity
@Table(name = "refresh_tokens", indexes = {
        @Index(name = "idx_refresh_tokens_user_revoked", columnList = "user_id, revoked_at"),
        @Index(name = "idx_refresh_tokens_family", columnList = "family_id"),
        @Index(name = "idx_refresh_tokens_expires_at", columnList = "expires_at")
})
public class RefreshToken {

    /** Reason a refresh token was revoked. Used for forensics and audit log. */
    public enum RevocationReason {
        /** Default: the token was rotated by a successful /auth/refresh call. */
        ROTATED,
        /** The user logged out explicitly. */
        LOGOUT,
        /** A token in the same family was presented twice. */
        REUSE_DETECTED,
        /** The user changed the password — all sessions are wiped. */
        PASSWORD_CHANGED,
        /** Administrative revocation (e.g. from the sessions panel). */
        ADMIN_REVOKED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Integer id;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", referencedColumnName = "id", nullable = false)
    private User user;

    @Column(name = "family_id", nullable = false, length = 36)
    private String familyId;

    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "revoked_reason", length = 32)
    private RevocationReason revokedReason;

    @Column(name = "replaced_by_hash", length = 64)
    private String replacedByHash;

    @Column(name = "remember_me", nullable = false)
    private boolean rememberMe;

    public Integer getId() {
        return id;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public RefreshToken setTokenHash(String tokenHash) {
        this.tokenHash = tokenHash;
        return this;
    }

    public User getUser() {
        return user;
    }

    public RefreshToken setUser(User user) {
        this.user = user;
        return this;
    }

    public String getFamilyId() {
        return familyId;
    }

    public RefreshToken setFamilyId(String familyId) {
        this.familyId = familyId;
        return this;
    }

    public LocalDateTime getIssuedAt() {
        return issuedAt;
    }

    public RefreshToken setIssuedAt(LocalDateTime issuedAt) {
        this.issuedAt = issuedAt;
        return this;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public RefreshToken setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
        return this;
    }

    public LocalDateTime getRevokedAt() {
        return revokedAt;
    }

    public RefreshToken setRevokedAt(LocalDateTime revokedAt) {
        this.revokedAt = revokedAt;
        return this;
    }

    public RevocationReason getRevokedReason() {
        return revokedReason;
    }

    public RefreshToken setRevokedReason(RevocationReason revokedReason) {
        this.revokedReason = revokedReason;
        return this;
    }

    public String getReplacedByHash() {
        return replacedByHash;
    }

    public RefreshToken setReplacedByHash(String replacedByHash) {
        this.replacedByHash = replacedByHash;
        return this;
    }

    public boolean isRememberMe() {
        return rememberMe;
    }

    public RefreshToken setRememberMe(boolean rememberMe) {
        this.rememberMe = rememberMe;
        return this;
    }

    /**
     * @return whether the token is currently usable — not revoked and not expired
     */
    public boolean isActive() {
        return revokedAt == null && expiresAt.isAfter(LocalDateTime.now());
    }
}
