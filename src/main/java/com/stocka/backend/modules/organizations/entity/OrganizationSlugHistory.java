package com.stocka.backend.modules.organizations.entity;

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
 * Stores the previous slugs an organization has used. Looking up an old slug returns the
 * current organization so the frontend can redirect deep links generated before a slug
 * rename (e.g. links shared in email notifications).
 */
@Entity
@Table(name = "organization_slug_history")
public class OrganizationSlugHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(name = "old_slug", nullable = false, unique = true, length = 40)
    private String oldSlug;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public Organization getOrganization() {
        return organization;
    }

    public OrganizationSlugHistory setOrganization(Organization organization) {
        this.organization = organization;
        return this;
    }

    public String getOldSlug() {
        return oldSlug;
    }

    public OrganizationSlugHistory setOldSlug(String oldSlug) {
        this.oldSlug = oldSlug;
        return this;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
