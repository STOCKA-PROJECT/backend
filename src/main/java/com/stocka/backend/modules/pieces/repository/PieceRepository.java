package com.stocka.backend.modules.pieces.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.stocka.backend.modules.locations.entity.Location;
import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.pieces.entity.Piece;
import com.stocka.backend.modules.piecetypes.entity.PieceType;

@Repository
public interface PieceRepository extends JpaRepository<Piece, Integer>, JpaSpecificationExecutor<Piece> {
    Page<Piece> findByOrganization(Organization organization, Pageable pageable);

    long countByLocation(Location location);

    /**
     * Number of (non-soft-deleted) pieces that include {@code pieceType} in their type set. Used
     * by the piece-types module to decide whether a type can be deleted.
     */
    @Query("SELECT COUNT(DISTINCT p) FROM Piece p JOIN p.pieceTypes t WHERE t = :type")
    long countByPieceTypesContaining(@Param("type") PieceType pieceType);

    /**
     * Pieces that include {@code pieceType} in their type set. Used by the bulk status recalc
     * after a type's attribute schema changes.
     */
    @Query("SELECT DISTINCT p FROM Piece p JOIN p.pieceTypes t WHERE t = :type")
    List<Piece> findByPieceTypesContaining(@Param("type") PieceType pieceType);

    Optional<Piece> findByIdAndOrganization(Integer id, Organization organization);
}
