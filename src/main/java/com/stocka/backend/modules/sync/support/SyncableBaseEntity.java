package com.stocka.backend.modules.sync.support;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;

/**
 * Base type for domain entities that participate in offline synchronization. Provides the
 * shared {@code syncId} (client-stable UUID identity) and {@code rev} (per-organization change
 * sequence cursor) columns and their accessors, plus a defensive {@code syncId} default on insert.
 *
 * <p>Subclasses only implement {@link #getSyncOrganizationId()} so the
 * {@link SyncStamper} can advance the correct organization change sequence. The {@code rev} is
 * assigned by the stamper in the service layer on every write; see
 * {@code docs/offline-sync/DESIGN.md}.
 *
 * @since 0.2.0
 */
@MappedSuperclass
public abstract class SyncableBaseEntity implements SyncableEntity {

    /** Client-stable synchronization id (UUID); identity used by offline clients. */
    @Column(name = "sync_id", length = 36, unique = true)
    private String syncId;

    /** Per-organization change-sequence value stamped on every write; the offline pull cursor. */
    @JsonIgnore
    @Column(name = "rev")
    private Long rev;

    /** Defensively assigns a {@code syncId} on insert when none was provided. */
    @PrePersist
    private void assignSyncIdIfMissing() {
        if (syncId == null) {
            syncId = UUID.randomUUID().toString();
        }
    }

    @Override
    public String getSyncId() {
        return syncId;
    }

    @Override
    public void setSyncId(String syncId) {
        this.syncId = syncId;
    }

    @Override
    public Long getRev() {
        return rev;
    }

    @Override
    public void setRev(Long rev) {
        this.rev = rev;
    }
}
