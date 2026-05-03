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
 * One-time token issued at signup (or via resend) so the user can prove ownership of
 * the email address they registered with. Stored hashed; the raw value only travels
 * inside the verification email URL.
 */
@Entity
@Table(name = "email_verification_tokens")
public class EmailVerificationToken {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Integer id;

    @Column(name = "token_hash", nullable = false, unique = true, length = 128)
    private String tokenHash;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", referencedColumnName = "id", nullable = false)
    private User user;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "used_at")
    private LocalDateTime usedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public Integer getId() {
        return id;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public EmailVerificationToken setTokenHash(String tokenHash) {
        this.tokenHash = tokenHash;
        return this;
    }

    public User getUser() {
        return user;
    }

    public EmailVerificationToken setUser(User user) {
        this.user = user;
        return this;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public EmailVerificationToken setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
        return this;
    }

    public LocalDateTime getUsedAt() {
        return usedAt;
    }

    public EmailVerificationToken setUsedAt(LocalDateTime usedAt) {
        this.usedAt = usedAt;
        return this;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public EmailVerificationToken setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
        return this;
    }
}
