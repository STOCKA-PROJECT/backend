package com.stocka.backend.modules.organizations.repository;

import java.util.Optional;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import com.stocka.backend.modules.organizations.entity.OrganizationSlugHistory;

@Repository
public interface OrganizationSlugHistoryRepository extends CrudRepository<OrganizationSlugHistory, Long> {

    /**
     * Looks up a slug that some organization used previously.
     *
     * @param oldSlug the historical slug
     * @return the matching history row, if any
     */
    Optional<OrganizationSlugHistory> findByOldSlug(String oldSlug);

    /**
     * Tells whether a slug appears anywhere in the history table.
     *
     * @param oldSlug candidate slug
     * @return {@code true} when at least one organization has used it
     */
    boolean existsByOldSlug(String oldSlug);
}
