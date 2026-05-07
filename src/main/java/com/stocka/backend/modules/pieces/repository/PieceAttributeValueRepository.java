package com.stocka.backend.modules.pieces.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.stocka.backend.modules.pieces.entity.Piece;
import com.stocka.backend.modules.pieces.entity.PieceAttributeValue;
import com.stocka.backend.modules.piecetypes.entity.PieceTypeAttribute;

@Repository
public interface PieceAttributeValueRepository extends JpaRepository<PieceAttributeValue, Integer> {
    List<PieceAttributeValue> findByPiece(Piece piece);

    List<PieceAttributeValue> findByPieceIn(List<Piece> pieces);

    long countByAttribute(PieceTypeAttribute attribute);

    @Modifying
    @Query("DELETE FROM PieceAttributeValue v WHERE v.attribute = :attribute")
    int deleteByAttribute(@Param("attribute") PieceTypeAttribute attribute);

    @Modifying
    @Query("DELETE FROM PieceAttributeValue v WHERE v.piece = :piece")
    int deleteByPiece(@Param("piece") Piece piece);
}
