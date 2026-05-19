package com.stocka.backend.modules.organizations.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.stocka.backend.modules.organizations.entity.OrganizationSlugHistory;

@Repository
public interface OrganizationSlugHistoryRepository extends CrudRepository<OrganizationSlugHistory, Long> {

    /**
     * Looks up a slug that some organization used previously. The associated organization is
     * eagerly loaded via {@code JOIN FETCH} so callers can access it without an open session.
     *
     * @param oldSlug the historical slug
     * @return the matching history row with its organization initialized, if any
     */
    @Query("SELECT h FROM OrganizationSlugHistory h JOIN FETCH h.organization WHERE h.oldSlug = :oldSlug")
    Optional<OrganizationSlugHistory> findByOldSlug(@Param("oldSlug") String oldSlug);

    /**
     * Tells whether a slug appears anywhere in the history table.
     *
     * @param oldSlug candidate slug
     * @return {@code true} when at least one organization has used it
     */
    boolean existsByOldSlug(String oldSlug);
}
