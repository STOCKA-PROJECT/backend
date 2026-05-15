package com.stocka.backend.modules.pieces.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
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
}
