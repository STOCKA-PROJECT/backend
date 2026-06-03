package com.stocka.backend.modules.sync.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Per-organization monotonic change sequence (the "CSN"). Every write to a synchronizable
 * entity of an organization bumps this counter and stamps the resulting value onto the row's
 * {@code rev} column, so offline clients can pull deltas with a {@code rev > checkpoint} cursor.
 *
 * <p>The counter is advanced with a row-locking {@code UPDATE} inside the caller's transaction
 * (see {@link com.stocka.backend.modules.sync.service.OrgChangeSequenceService}), which guarantees
 * that the order in which {@code rev} values are assigned equals the order in which the owning
 * transactions commit — a gap-free, resumable cursor.
 *
 * @since 0.2.0
 */
@Entity
@Table(name = "org_change_sequence")
public class OrgChangeSequence {

    /** Owning organization id; also the primary key (one counter row per organization). */
    @Id
    @Column(name = "organization_id", nullable = false)
    private Integer organizationId;

    /** Current (last assigned) sequence value for the organization. Starts at 0. */
    @Column(name = "seq_value", nullable = false)
    private long value;

    public Integer getOrganizationId() {
        return organizationId;
    }

    public OrgChangeSequence setOrganizationId(Integer organizationId) {
        this.organizationId = organizationId;
        return this;
    }

    public long getValue() {
        return value;
    }

    public OrgChangeSequence setValue(long value) {
        this.value = value;
        return this;
    }
}
