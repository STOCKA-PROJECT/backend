package com.stocka.backend.modules.piecetypes.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.stocka.backend.modules.piecetypes.entity.PieceType;
import com.stocka.backend.modules.piecetypes.entity.PieceTypeAttribute;

@Repository
public interface PieceTypeAttributeRepository extends JpaRepository<PieceTypeAttribute, Integer> {
    List<PieceTypeAttribute> findByPieceTypeOrderByPositionAscIdAsc(PieceType pieceType);

    Optional<PieceTypeAttribute> findByPieceTypeAndName(PieceType pieceType, String name);
}
