package com.stocka.backend.modules.sync.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.stocka.backend.modules.sync.entity.OrgChangeSequence;

/**
 * Data access for the per-organization change sequence. The increment is expressed as a single
 * atomic {@code UPDATE} so the database holds a row lock until the surrounding transaction commits.
 *
 * @since 0.2.0
 */
public interface OrgChangeSequenceRepository extends JpaRepository<OrgChangeSequence, Integer> {

    /**
     * Atomically advances the counter for the organization by one.
     *
     * @param orgId organization id
     * @return number of rows updated ({@code 1} when the counter row exists, {@code 0} otherwise)
     */
    @Modifying
    @Query("UPDATE OrgChangeSequence s SET s.value = s.value + 1 WHERE s.organizationId = :orgId")
    int increment(@Param("orgId") Integer orgId);

    /**
     * Reads the current counter value for the organization.
     *
     * @param orgId organization id
     * @return the current value, or empty when no counter row exists yet
     */
    @Query("SELECT s.value FROM OrgChangeSequence s WHERE s.organizationId = :orgId")
    Optional<Long> currentValue(@Param("orgId") Integer orgId);
}
