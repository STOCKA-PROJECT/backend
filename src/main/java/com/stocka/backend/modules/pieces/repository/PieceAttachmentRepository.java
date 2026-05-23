package com.stocka.backend.modules.pieces.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.pieces.entity.Piece;
import com.stocka.backend.modules.pieces.entity.PieceAttachment;
import com.stocka.backend.modules.pieces.entity.PieceAttachmentKind;

@Repository
public interface PieceAttachmentRepository extends JpaRepository<PieceAttachment, Integer> {
    List<PieceAttachment> findByPiece(Piece piece);

    List<PieceAttachment> findByPieceAndKind(Piece piece, PieceAttachmentKind kind);

    long countByPieceAndKind(Piece piece, PieceAttachmentKind kind);

    /**
     * Sum of {@code size_bytes} of every non-soft-deleted attachment belonging to a non-soft-deleted
     * piece in {@code organization}. Returns {@code 0} when there are no rows. Used by the
     * per-org storage quota check (issue #21).
     */
    @Query("SELECT COALESCE(SUM(a.sizeBytes), 0) FROM PieceAttachment a "
            + "WHERE a.piece.organization = :organization")
    long sumSizeBytesByOrganization(@Param("organization") Organization organization);

    /**
     * Bulk soft-delete every still-active attachment of {@code piece}. Used by the
     * piece cascade so attachments do not dangle when their parent piece is soft-deleted.
     * The blob in R2 is intentionally NOT removed here — only the explicit per-attachment
     * delete should drop the file, so a future organization restore can recover them.
     *
     * @param piece parent whose attachments must be soft-deleted
     * @return number of rows affected
     */
    @Modifying
    @Query("update PieceAttachment a set a.deletedAt = CURRENT_TIMESTAMP "
            + "where a.piece = ?1 and a.deletedAt is null")
    int softDeleteByPiece(Piece piece);

    /**
     * Bulk soft-delete every still-active attachment of any piece in {@code organization}.
     * Used by the organization cascade to flatten the work into a single SQL UPDATE.
     *
     * @param organization grand-parent whose nested attachments must be soft-deleted
     * @return number of rows affected
     */
    @Modifying
    @Query("update PieceAttachment a set a.deletedAt = CURRENT_TIMESTAMP "
            + "where a.piece.organization = ?1 and a.deletedAt is null")
    int softDeleteByOrganization(Organization organization);
}
