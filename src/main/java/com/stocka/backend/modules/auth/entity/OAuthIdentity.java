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
import jakarta.persistence.UniqueConstraint;

/**
 * Persistent link between a user and an external OAuth identity provider
 * (Feature 4). Today only Google is supported; the {@code provider} column
 * keeps the table future-proof for Microsoft / Apple etc.
 *
 * <p>The {@code (provider, providerUserId)} pair is unique — Google's
 * {@code sub} claim is the stable identifier even if the email later changes
 * on the Google side.
 */
@Entity
@Table(name = "oauth_identities",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_oauth_provider_subject",
                columnNames = {"provider", "provider_user_id"}),
        indexes = @Index(name = "idx_oauth_user", columnList = "user_id"))
public class OAuthIdentity {

    public enum Provider {
        GOOGLE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", referencedColumnName = "id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 16)
    private Provider provider;

    @Column(name = "provider_user_id", nullable = false, length = 128)
    private String providerUserId;

    @Column(name = "email", length = 255)
    private String email;

    @Column(name = "linked_at", nullable = false)
    private LocalDateTime linkedAt;

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public OAuthIdentity setUser(User user) {
        this.user = user;
        return this;
    }

    public Provider getProvider() {
        return provider;
    }

    public OAuthIdentity setProvider(Provider provider) {
        this.provider = provider;
        return this;
    }

    public String getProviderUserId() {
        return providerUserId;
    }

    public OAuthIdentity setProviderUserId(String providerUserId) {
        this.providerUserId = providerUserId;
        return this;
    }

    public String getEmail() {
        return email;
    }

    public OAuthIdentity setEmail(String email) {
        this.email = email;
        return this;
    }

    public LocalDateTime getLinkedAt() {
        return linkedAt;
    }

    public OAuthIdentity setLinkedAt(LocalDateTime linkedAt) {
        this.linkedAt = linkedAt;
        return this;
    }
}
