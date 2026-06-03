package com.stocka.backend.modules.sync.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.stocka.backend.modules.sync.entity.OrgChangeSequence;
import com.stocka.backend.modules.sync.repository.OrgChangeSequenceRepository;

/**
 * Hands out the next monotonic {@code rev} for an organization (the "CSN", see
 * {@link OrgChangeSequence}). Must run inside the caller's transaction so the counter advance and
 * the entity write commit atomically and in the same order.
 *
 * @since 0.2.0
 */
@Service
public class OrgChangeSequenceService {

    private final OrgChangeSequenceRepository repository;

    public OrgChangeSequenceService(OrgChangeSequenceRepository repository) {
        this.repository = repository;
    }

    /**
     * Advances the organization's change sequence and returns the new value. The atomic
     * {@code UPDATE} holds a row lock until commit, so concurrent callers for the same organization
     * serialize and never receive a value that another transaction may commit out of order.
     *
     * @param orgId organization id (must not be {@code null})
     * @return the freshly assigned, strictly increasing revision for the organization
     * @throws IllegalArgumentException when {@code orgId} is {@code null}
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public long next(Integer orgId) {
        if (orgId == null) {
            throw new IllegalArgumentException("orgId must not be null");
        }
        int updated = repository.increment(orgId);
        if (updated == 0) {
            // First write for this organization: lazily create the counter row at value 1.
            try {
                repository.save(new OrgChangeSequence().setOrganizationId(orgId).setValue(1L));
            } catch (DataIntegrityViolationException concurrentInsert) {
                // Another transaction seeded the row first; just advance it.
                repository.increment(orgId);
            }
        }
        return repository.currentValue(orgId)
                .orElseThrow(() -> new IllegalStateException(
                        "change sequence missing after increment for org " + orgId));
    }
}
