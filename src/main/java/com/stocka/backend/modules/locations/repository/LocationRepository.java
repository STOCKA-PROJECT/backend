package com.stocka.backend.modules.locations.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import com.stocka.backend.modules.locations.entity.Location;
import com.stocka.backend.modules.organizations.entity.Organization;

@Repository
public interface LocationRepository extends CrudRepository<Location, Integer> {
    List<Location> findByOrganization(Organization organization);

    List<Location> findByOrganizationAndParentIsNull(Organization organization);

    List<Location> findByParent(Location parent);

    long countByParent(Location parent);

    Optional<Location> findByOrganizationAndParentAndName(Organization organization, Location parent, String name);

    Optional<Location> findByOrganizationAndParentIsNullAndName(Organization organization, String name);

    /**
     * Finds a live (non-deleted) location by its synchronization id.
     *
     * @param syncId client-stable sync id
     * @return the location, or empty when missing or soft-deleted
     */
    Optional<Location> findBySyncId(String syncId);

    /**
     * Bulk soft-delete every still-active location of {@code organization}. Used by the
     * organization cascade so locations do not dangle when their parent org is removed.
     *
     * @param organization owner whose locations must be soft-deleted
     * @return number of rows affected
     */
    @Modifying
    @Query("update Location l set l.deletedAt = CURRENT_TIMESTAMP "
            + "where l.organization = ?1 and l.deletedAt is null")
    int softDeleteByOrganization(Organization organization);
}
