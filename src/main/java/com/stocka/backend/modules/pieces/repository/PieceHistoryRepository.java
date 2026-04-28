package com.stocka.backend.modules.pieces.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.stocka.backend.modules.pieces.entity.Piece;
import com.stocka.backend.modules.pieces.entity.PieceHistory;

@Repository
public interface PieceHistoryRepository extends JpaRepository<PieceHistory, Long> {
    Page<PieceHistory> findByPieceOrderByCreatedAtDescIdDesc(Piece piece, Pageable pageable);
}
