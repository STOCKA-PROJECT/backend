package com.stocka.backend.modules.piecetypes.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.piecetypes.entity.PieceType;
import com.stocka.backend.modules.piecetypes.entity.PieceTypeAction;

@Repository
public interface PieceTypeActionRepository extends JpaRepository<PieceTypeAction, Integer> {
    List<PieceTypeAction> findByPieceTypeOrderByPositionAscIdAsc(PieceType pieceType);

    Optional<PieceTypeAction> findByPieceTypeAndName(PieceType pieceType, String name);

    /**
     * Bulk soft-delete every still-active action of {@code pieceType}. Used by the piece-type
     * cascade so actions do not dangle when their parent type is removed.
     *
     * @param pieceType owner whose actions must be soft-deleted
     * @return number of rows affected
     */
    @Modifying
    @Query("update PieceTypeAction a set a.deletedAt = CURRENT_TIMESTAMP "
            + "where a.pieceType = ?1 and a.deletedAt is null")
    int softDeleteByPieceType(PieceType pieceType);

    /**
     * Bulk soft-delete every still-active action whose owning piece-type belongs to
     * {@code organization}. Used by the organization cascade.
     *
     * @param organization grand-parent whose nested actions must be soft-deleted
     * @return number of rows affected
     */
    @Modifying
    @Query("update PieceTypeAction a set a.deletedAt = CURRENT_TIMESTAMP "
            + "where a.pieceType.organization = ?1 and a.deletedAt is null")
    int softDeleteByOrganization(Organization organization);
}
