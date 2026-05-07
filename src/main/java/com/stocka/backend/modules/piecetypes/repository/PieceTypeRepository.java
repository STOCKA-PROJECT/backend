package com.stocka.backend.modules.piecetypes.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.piecetypes.entity.PieceType;

@Repository
public interface PieceTypeRepository extends JpaRepository<PieceType, Integer> {
    List<PieceType> findByOrganization(Organization organization);

    Optional<PieceType> findByOrganizationAndName(Organization organization, String name);
}
