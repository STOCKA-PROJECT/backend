package com.stocka.backend.modules.piecetypes.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.piecetypes.entity.PieceType;

@Repository
public interface PieceTypeRepository extends JpaRepository<PieceType, Integer> {
    List<PieceType> findByOrganization(Organization organization);

    Optional<PieceType> findByOrganizationAndName(Organization organization, String name);

    /**
     * Bulk soft-delete every still-active piece type of {@code organization}. Used by the
     * organization cascade so types do not dangle when their parent org is removed.
     *
     * @param organization owner whose piece types must be soft-deleted
     * @return number of rows affected
     */
    @Modifying
    @Query("update PieceType t set t.deletedAt = CURRENT_TIMESTAMP "
            + "where t.organization = ?1 and t.deletedAt is null")
    int softDeleteByOrganization(Organization organization);
}
