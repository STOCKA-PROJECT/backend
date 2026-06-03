package com.stocka.backend.modules.sync.entity;

import java.util.Date;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Idempotency record for a processed sync push mutation. Keyed by the client-generated
 * {@code mutationId}; a repeated id short-circuits to a {@code duplicate} result so retries after a
 * lost response never re-apply a write (DECISIONS-AND-RISKS R24).
 *
 * @since 0.2.0
 */
@Entity
@Table(name = "sync_mutation")
public class SyncMutation {

    /** Client-generated idempotency key (UUID). */
    @Id
    @Column(name = "mutation_id", length = 36, nullable = false)
    private String mutationId;

    /** Organization the mutation was applied to. */
    @Column(name = "organization_id", nullable = false)
    private Integer organizationId;

    /** The {@code rev} assigned to the affected row when applied, or {@code null} on conflict. */
    @Column(name = "applied_rev")
    private Long appliedRev;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Date createdAt;

    public String getMutationId() {
        return mutationId;
    }

    public SyncMutation setMutationId(String mutationId) {
        this.mutationId = mutationId;
        return this;
    }

    public Integer getOrganizationId() {
        return organizationId;
    }

    public SyncMutation setOrganizationId(Integer organizationId) {
        this.organizationId = organizationId;
        return this;
    }

    public Long getAppliedRev() {
        return appliedRev;
    }

    public SyncMutation setAppliedRev(Long appliedRev) {
        this.appliedRev = appliedRev;
        return this;
    }

    public Date getCreatedAt() {
        return createdAt;
    }
}
