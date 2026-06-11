package com.stocka.backend.modules.timelines.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.timelines.entity.Timeline;
import com.stocka.backend.modules.timelines.entity.TimelineScene;

@Repository
public interface TimelineSceneRepository extends JpaRepository<TimelineScene, Integer> {
    Optional<TimelineScene> findByTimeline(Timeline timeline);

    /**
     * Bulk soft-delete the scene of {@code timeline} (if any). Used by the timeline cascade.
     *
     * @param timeline owner whose scene must be soft-deleted
     * @return number of rows affected
     */
    @Modifying
    @Query("update TimelineScene s set s.deletedAt = CURRENT_TIMESTAMP "
            + "where s.timeline = ?1 and s.deletedAt is null")
    int softDeleteByTimeline(Timeline timeline);

    /**
     * Bulk soft-delete every scene whose timeline belongs to {@code organization}. Used by the
     * organization cascade.
     *
     * @param organization grand-parent whose nested scenes must be soft-deleted
     * @return number of rows affected
     */
    @Modifying
    @Query("update TimelineScene s set s.deletedAt = CURRENT_TIMESTAMP "
            + "where s.timeline.organization = ?1 and s.deletedAt is null")
    int softDeleteByOrganization(Organization organization);
}
