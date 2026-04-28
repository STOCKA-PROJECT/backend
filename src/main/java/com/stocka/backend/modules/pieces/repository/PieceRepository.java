package com.stocka.backend.modules.pieces.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import com.stocka.backend.modules.locations.entity.Location;
import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.pieces.entity.Piece;
import com.stocka.backend.modules.piecetypes.entity.PieceType;

@Repository
public interface PieceRepository extends JpaRepository<Piece, Integer>, JpaSpecificationExecutor<Piece> {
    Page<Piece> findByOrganization(Organization organization, Pageable pageable);

    long countByPieceType(PieceType pieceType);

    long countByLocation(Location location);

    List<Piece> findByPieceType(PieceType pieceType);

    Optional<Piece> findByIdAndOrganization(Integer id, Organization organization);
}
