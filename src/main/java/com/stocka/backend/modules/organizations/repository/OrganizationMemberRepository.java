package com.stocka.backend.modules.organizations.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.organizations.entity.OrganizationMember;
import com.stocka.backend.modules.organizations.entity.OrganizationRoleEnum;
import com.stocka.backend.modules.roles.entity.RoleEnum;
import com.stocka.backend.modules.users.entity.User;

@Repository
public interface OrganizationMemberRepository extends CrudRepository<OrganizationMember, Integer> {
    Optional<OrganizationMember> findByUserAndOrganization(User user, Organization organization);

    List<OrganizationMember> findByOrganization(Organization organization);

    List<OrganizationMember> findByUser(User user);

    long countByOrganizationAndRole(Organization organization, OrganizationRoleEnum role);

    long countByUserAndRole(User user, OrganizationRoleEnum role);

    /**
     * Whether {@code organization} has at least one OWNER member whose global user role is ADMIN.
     * Resolved with a single query (joining member &rarr; user &rarr; role) so it can run safely
     * from {@code @PreAuthorize} expressions, outside an open transaction, without triggering lazy
     * loads. Soft-deleted members and users are excluded by their {@code @SQLRestriction}.
     *
     * @param organization organization to inspect
     * @return {@code true} when an admin owner exists
     */
    @Query("select case when count(m) > 0 then true else false end from OrganizationMember m "
            + "where m.organization = :organization "
            + "and m.role = com.stocka.backend.modules.organizations.entity.OrganizationRoleEnum.OWNER "
            + "and m.user.role.name = :adminRole")
    boolean existsAdminOwner(@Param("organization") Organization organization, @Param("adminRole") RoleEnum adminRole);
}
