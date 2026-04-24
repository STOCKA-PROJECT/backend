package com.stocka.backend.modules.organizations.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.organizations.entity.OrganizationMember;
import com.stocka.backend.modules.organizations.entity.OrganizationRoleEnum;
import com.stocka.backend.modules.users.entity.User;

@Repository
public interface OrganizationMemberRepository extends CrudRepository<OrganizationMember, Integer> {
    Optional<OrganizationMember> findByUserAndOrganization(User user, Organization organization);

    List<OrganizationMember> findByOrganization(Organization organization);

    List<OrganizationMember> findByUser(User user);

    long countByOrganizationAndRole(Organization organization, OrganizationRoleEnum role);
}
