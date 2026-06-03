package com.stocka.backend.modules.piecetypes.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.piecetypes.entity.PieceType;
import com.stocka.backend.modules.piecetypes.entity.PieceTypeAttribute;

@Repository
public interface PieceTypeAttributeRepository extends JpaRepository<PieceTypeAttribute, Integer> {
    List<PieceTypeAttribute> findByPieceTypeOrderByPositionAscIdAsc(PieceType pieceType);

    Optional<PieceTypeAttribute> findByPieceTypeAndName(PieceType pieceType, String name);

    /**
     * Finds a live (non-deleted) type attribute by its synchronization id.
     *
     * @param syncId client-stable sync id
     * @return the attribute, or empty when missing or soft-deleted
     */
    Optional<PieceTypeAttribute> findBySyncId(String syncId);

    /**
     * Bulk soft-delete every still-active attribute of {@code pieceType}. Used by the
     * piece-type cascade so attributes do not dangle when their parent type is removed.
     *
     * @param pieceType owner whose attributes must be soft-deleted
     * @return number of rows affected
     */
    @Modifying
    @Query("update PieceTypeAttribute a set a.deletedAt = CURRENT_TIMESTAMP "
            + "where a.pieceType = ?1 and a.deletedAt is null")
    int softDeleteByPieceType(PieceType pieceType);

    /**
     * Bulk soft-delete every still-active attribute whose owning piece-type belongs to
     * {@code organization}. Used by the organization cascade.
     *
     * @param organization grand-parent whose nested attributes must be soft-deleted
     * @return number of rows affected
     */
    @Modifying
    @Query("update PieceTypeAttribute a set a.deletedAt = CURRENT_TIMESTAMP "
            + "where a.pieceType.organization = ?1 and a.deletedAt is null")
    int softDeleteByOrganization(Organization organization);
}
