package com.stocka.backend.modules.organizations.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.organizations.entity.OrganizationPieceAttribute;

@Repository
public interface OrganizationPieceAttributeRepository
        extends JpaRepository<OrganizationPieceAttribute, Integer> {

    List<OrganizationPieceAttribute> findByOrganizationOrderByPositionAscIdAsc(Organization organization);

    Optional<OrganizationPieceAttribute> findByOrganizationAndName(Organization organization, String name);

    /**
     * Finds a live (non-deleted) organization attribute by its synchronization id.
     *
     * @param syncId client-stable sync id
     * @return the attribute, or empty when missing or soft-deleted
     */
    Optional<OrganizationPieceAttribute> findBySyncId(String syncId);

    /**
     * Bulk soft-delete every still-active organization-piece-attribute of
     * {@code organization}. Used by the organization cascade.
     *
     * @param organization owner whose attributes must be soft-deleted
     * @return number of rows affected
     */
    @Modifying
    @Query("update OrganizationPieceAttribute a set a.deletedAt = CURRENT_TIMESTAMP "
            + "where a.organization = ?1 and a.deletedAt is null")
    int softDeleteByOrganization(Organization organization);
}
