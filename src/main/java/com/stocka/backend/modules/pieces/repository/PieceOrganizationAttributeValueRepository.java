package com.stocka.backend.modules.pieces.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.stocka.backend.modules.organizations.entity.OrganizationPieceAttribute;
import com.stocka.backend.modules.pieces.entity.Piece;
import com.stocka.backend.modules.pieces.entity.PieceOrganizationAttributeValue;

@Repository
public interface PieceOrganizationAttributeValueRepository
        extends JpaRepository<PieceOrganizationAttributeValue, Integer> {

    List<PieceOrganizationAttributeValue> findByPiece(Piece piece);

    List<PieceOrganizationAttributeValue> findByPieceIn(List<Piece> pieces);

    long countByAttribute(OrganizationPieceAttribute attribute);

    @Modifying
    @Query("DELETE FROM PieceOrganizationAttributeValue v WHERE v.attribute = :attribute")
    int deleteByAttribute(@Param("attribute") OrganizationPieceAttribute attribute);

    @Modifying
    @Query("DELETE FROM PieceOrganizationAttributeValue v WHERE v.piece = :piece")
    int deleteByPiece(@Param("piece") Piece piece);
}
