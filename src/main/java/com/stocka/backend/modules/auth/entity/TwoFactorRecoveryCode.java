package com.stocka.backend.modules.auth.entity;

import java.time.LocalDateTime;

import com.stocka.backend.modules.users.entity.User;

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
 * One of the user's 10 recovery codes generated when 2FA is activated.
 * Stored as a BCrypt hash — recovery codes are short (10 characters with the
 * dash separator) so BCrypt's slow comparison is exactly what we want to
 * frustrate brute force.
 *
 * <p>Used once: {@code usedAt} is stamped when consumed. Regenerating the
 * codes deletes the entire previous batch.
 */
@Entity
@Table(name = "two_factor_recovery_codes", indexes = {
        @Index(name = "idx_2fa_recovery_user_used", columnList = "user_id, used_at")
})
public class TwoFactorRecoveryCode {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", referencedColumnName = "id", nullable = false)
    private User user;

    @Column(name = "code_hash", nullable = false, length = 100)
    private String codeHash;

    @Column(name = "used_at")
    private LocalDateTime usedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public TwoFactorRecoveryCode setUser(User user) {
        this.user = user;
        return this;
    }

    public String getCodeHash() {
        return codeHash;
    }

    public TwoFactorRecoveryCode setCodeHash(String codeHash) {
        this.codeHash = codeHash;
        return this;
    }

    public LocalDateTime getUsedAt() {
        return usedAt;
    }

    public TwoFactorRecoveryCode setUsedAt(LocalDateTime usedAt) {
        this.usedAt = usedAt;
        return this;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public TwoFactorRecoveryCode setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
        return this;
    }
}
