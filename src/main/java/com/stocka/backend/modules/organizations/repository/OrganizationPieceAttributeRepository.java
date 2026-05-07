package com.stocka.backend.modules.organizations.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.organizations.entity.OrganizationPieceAttribute;

@Repository
public interface OrganizationPieceAttributeRepository
        extends JpaRepository<OrganizationPieceAttribute, Integer> {

    List<OrganizationPieceAttribute> findByOrganizationOrderByPositionAscIdAsc(Organization organization);

    Optional<OrganizationPieceAttribute> findByOrganizationAndName(Organization organization, String name);
}
