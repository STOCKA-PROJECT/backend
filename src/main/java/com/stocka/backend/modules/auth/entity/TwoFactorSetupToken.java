package com.stocka.backend.modules.auth.entity;

import java.time.LocalDateTime;

import com.stocka.backend.modules.users.entity.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Short-lived (15 min) token issued by {@code POST /auth/2fa/setup}. Holds the
 * candidate TOTP secret <em>before</em> the user confirms it with a code, so
 * a half-completed setup never leaves a stale row on the {@code users} table.
 *
 * <p>Consumed atomically by {@code POST /auth/2fa/confirm}: on success the
 * secret is copied to the user, the recovery codes are minted, and this row's
 * {@code consumed} is flipped.
 */
@Entity
@Table(name = "two_factor_setup_tokens")
public class TwoFactorSetupToken {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", referencedColumnName = "id", nullable = false)
    private User user;

    @Column(name = "setup_token_hash", nullable = false, unique = true, length = 64)
    private String setupTokenHash;

    /** AES-GCM encrypted candidate secret. */
    @Column(name = "encrypted_secret", nullable = false, length = 255)
    private String encryptedSecret;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "consumed", nullable = false)
    private boolean consumed = false;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public TwoFactorSetupToken setUser(User user) {
        this.user = user;
        return this;
    }

    public String getSetupTokenHash() {
        return setupTokenHash;
    }

    public TwoFactorSetupToken setSetupTokenHash(String setupTokenHash) {
        this.setupTokenHash = setupTokenHash;
        return this;
    }

    public String getEncryptedSecret() {
        return encryptedSecret;
    }

    public TwoFactorSetupToken setEncryptedSecret(String encryptedSecret) {
        this.encryptedSecret = encryptedSecret;
        return this;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public TwoFactorSetupToken setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
        return this;
    }

    public boolean isConsumed() {
        return consumed;
    }

    public TwoFactorSetupToken setConsumed(boolean consumed) {
        this.consumed = consumed;
        return this;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public TwoFactorSetupToken setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
        return this;
    }
}
