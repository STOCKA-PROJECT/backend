package com.stocka.backend.modules.sync.support;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.stocka.backend.modules.sync.service.OrgChangeSequenceService;

/**
 * Stamps synchronization metadata onto a {@link SyncableEntity} immediately before it is persisted.
 * Call {@link #stamp(SyncableEntity)} at the end of every mutating service method (create, update,
 * soft-delete) so both the web REST path and the offline sync push converge to the same model:
 *
 * <ul>
 *   <li>assigns a {@code syncId} when missing (server-side creation);</li>
 *   <li>advances the owning organization's change sequence and records the new {@code rev}.</li>
 * </ul>
 *
 * <p>Stamping happens in the service layer (before {@code save}), not inside a Hibernate flush
 * listener, so advancing the counter never triggers a nested flush.
 *
 * @since 0.2.0
 */
@Component
public class SyncStamper {

    private final OrgChangeSequenceService changeSequence;

    public SyncStamper(OrgChangeSequenceService changeSequence) {
        this.changeSequence = changeSequence;
    }

    /**
     * Stamps {@code syncId} (when absent) and a fresh {@code rev} onto the entity.
     *
     * @param entity the entity about to be persisted (must not be {@code null} and must expose a
     *               non-{@code null} owning organization id)
     */
    public void stamp(SyncableEntity entity) {
        if (entity.getSyncId() == null) {
            entity.setSyncId(UUID.randomUUID().toString());
        }
        entity.setRev(changeSequence.next(entity.getSyncOrganizationId()));
    }
}
