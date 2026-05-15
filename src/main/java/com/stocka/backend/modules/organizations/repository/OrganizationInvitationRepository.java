package com.stocka.backend.modules.organizations.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.stocka.backend.modules.organizations.entity.InvitationStatus;
import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.organizations.entity.OrganizationInvitation;

@Repository
public interface OrganizationInvitationRepository extends JpaRepository<OrganizationInvitation, Integer> {
    Optional<OrganizationInvitation> findByToken(String token);

    Optional<OrganizationInvitation> findByOrganizationAndEmailAndStatus(
            Organization organization, String email, InvitationStatus status);

    List<OrganizationInvitation> findByOrganizationAndStatus(Organization organization, InvitationStatus status);

    List<OrganizationInvitation> findByEmailAndStatus(String email, InvitationStatus status);

    List<OrganizationInvitation> findByEmailOrderByCreatedAtDesc(String email);

    long countByOrganizationAndStatus(Organization organization, InvitationStatus status);
}
