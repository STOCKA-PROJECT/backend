package com.stocka.backend.modules.sync.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.stocka.backend.modules.sync.entity.SyncMutation;

/**
 * Persistence for sync push idempotency records.
 *
 * @since 0.2.0
 */
public interface SyncMutationRepository extends JpaRepository<SyncMutation, String> {
}
