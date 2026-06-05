package com.stocka.backend.modules.timelines.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.timelines.entity.Timeline;

@Repository
public interface TimelineRepository extends JpaRepository<Timeline, Integer> {
    List<Timeline> findByOrganization(Organization organization);

    Optional<Timeline> findByOrganizationAndName(Organization organization, String name);

    /**
     * Bulk soft-delete every still-active timeline of {@code organization}. Used by the
     * organization cascade so timelines do not dangle when their parent org is removed.
     *
     * @param organization owner whose timelines must be soft-deleted
     * @return number of rows affected
     */
    @Modifying
    @Query("update Timeline t set t.deletedAt = CURRENT_TIMESTAMP "
            + "where t.organization = ?1 and t.deletedAt is null")
    int softDeleteByOrganization(Organization organization);
}
