package com.stocka.backend.modules.pieces.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.stocka.backend.modules.pieces.entity.Piece;
import com.stocka.backend.modules.pieces.entity.PieceAttachment;
import com.stocka.backend.modules.pieces.entity.PieceAttachmentKind;

@Repository
public interface PieceAttachmentRepository extends JpaRepository<PieceAttachment, Integer> {
    List<PieceAttachment> findByPiece(Piece piece);

    List<PieceAttachment> findByPieceAndKind(Piece piece, PieceAttachmentKind kind);

    long countByPieceAndKind(Piece piece, PieceAttachmentKind kind);
}
