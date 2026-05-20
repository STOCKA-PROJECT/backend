package com.stocka.backend.modules.users.entity;

import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Stores the previous usernames a user has used. Looking up an old username returns the
 * current user so callers can decide whether the slot is still tied to an active account
 * (and thus claimable by no one) or has been released after the owner was deleted.
 */
@Entity
@Table(name = "user_username_history")
public class UserUsernameHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "old_username", nullable = false, unique = true, length = 40)
    private String oldUsername;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public UserUsernameHistory setUser(User user) {
        this.user = user;
        return this;
    }

    public String getOldUsername() {
        return oldUsername;
    }

    public UserUsernameHistory setOldUsername(String oldUsername) {
        this.oldUsername = oldUsername;
        return this;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
