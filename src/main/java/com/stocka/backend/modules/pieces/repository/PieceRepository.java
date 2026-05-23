package com.stocka.backend.modules.pieces.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
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

    long countByOrganization(Organization organization);

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

    /**
     * All non-soft-deleted pieces of an organization. Used to recompute status in bulk after the
     * organization's attribute schema changes.
     */
    List<Piece> findByOrganization(Organization organization);

    /**
     * Whether another (non-deleted) piece in {@code organizationId} already has the given
     * {@code serialNumber}. Used to enforce per-organization uniqueness from the service layer
     * without an actual DB UNIQUE constraint (MariaDB cannot scope UNIQUE by {@code deleted_at}).
     */
    boolean existsByOrganization_IdAndSerialNumber(Integer organizationId, String serialNumber);

    /**
     * Same as {@link #existsByOrganization_IdAndSerialNumber} but excluding the piece with id
     * {@code excludePieceId} — used during update to allow re-saving the same value.
     */
    boolean existsByOrganization_IdAndSerialNumberAndIdNot(
            Integer organizationId, String serialNumber, Integer excludePieceId);

    /**
     * Bulk soft-delete every still-active piece of {@code organization}. Invoked when an
     * organization itself is being soft-deleted so children do not remain referencing a
     * filtered-out parent (see {@code @SQLRestriction} on {@code Organization}).
     *
     * @param organization owner whose pieces must be soft-deleted
     * @return number of rows affected
     */
    @Modifying
    @Query("update Piece p set p.deletedAt = CURRENT_TIMESTAMP "
            + "where p.organization = ?1 and p.deletedAt is null")
    int softDeleteByOrganization(Organization organization);
}
