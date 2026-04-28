package com.stocka.backend.modules.organizations.security;

import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.organizations.entity.OrganizationMember;
import com.stocka.backend.modules.organizations.entity.OrganizationRoleEnum;
import com.stocka.backend.modules.organizations.repository.OrganizationMemberRepository;
import com.stocka.backend.modules.organizations.repository.OrganizationRepository;
import com.stocka.backend.modules.roles.entity.RoleEnum;
import com.stocka.backend.modules.users.entity.User;

/**
 * Authorization helpers used from {@code @PreAuthorize} expressions on REST controllers.
 *
 * <p>Bean name {@code orgSecurity} so SpEL can reference it as
 * {@code @PreAuthorize("@orgSecurity.canWritePieces(#orgId, principal)")}.
 */
@Component("orgSecurity")
public class OrganizationSecurity {
    private final OrganizationRepository organizationRepository;
    private final OrganizationMemberRepository memberRepository;

    public OrganizationSecurity(
            OrganizationRepository organizationRepository,
            OrganizationMemberRepository memberRepository
    ) {
        this.organizationRepository = organizationRepository;
        this.memberRepository = memberRepository;
    }

    public boolean isMember(Integer orgId, Object principal) {
        return hasAnyRole(orgId, principal, Set.of(
                OrganizationRoleEnum.OWNER,
                OrganizationRoleEnum.MANAGER,
                OrganizationRoleEnum.USER,
                OrganizationRoleEnum.SPECTATOR
        ));
    }

    public boolean isOwner(Integer orgId, Object principal) {
        return hasAnyRole(orgId, principal, Set.of(OrganizationRoleEnum.OWNER));
    }

    public boolean isOwnerOrManager(Integer orgId, Object principal) {
        return hasAnyRole(orgId, principal, Set.of(
                OrganizationRoleEnum.OWNER,
                OrganizationRoleEnum.MANAGER
        ));
    }

    public boolean isSpectator(Integer orgId, Object principal) {
        return hasAnyRole(orgId, principal, Set.of(OrganizationRoleEnum.SPECTATOR));
    }

    /**
     * Anyone in the organization (including SPECTATOR) can read its content.
     * Alias of {@link #isMember(Integer, Object)} kept for semantic clarity at call sites.
     */
    public boolean canReadOrgContent(Integer orgId, Object principal) {
        return isMember(orgId, principal);
    }

    /**
     * OWNER, MANAGER and USER can create/modify/delete pieces and their attachments.
     * SPECTATOR cannot.
     */
    public boolean canWritePieces(Integer orgId, Object principal) {
        return hasAnyRole(orgId, principal, Set.of(
                OrganizationRoleEnum.OWNER,
                OrganizationRoleEnum.MANAGER,
                OrganizationRoleEnum.USER
        ));
    }

    /**
     * Only OWNER and MANAGER can create/modify/delete locations and piece types.
     */
    public boolean canManageOrgContent(Integer orgId, Object principal) {
        return isOwnerOrManager(orgId, principal);
    }

    private boolean hasAnyRole(Integer orgId, Object principal, Set<OrganizationRoleEnum> allowed) {
        if (orgId == null || !(principal instanceof User user)) {
            return false;
        }
        if (isGlobalAdmin(user)) {
            return organizationRepository.findById(orgId).isPresent();
        }
        Optional<Organization> orgOpt = organizationRepository.findById(orgId);
        if (orgOpt.isEmpty()) {
            return false;
        }
        Optional<OrganizationMember> memberOpt =
                memberRepository.findByUserAndOrganization(user, orgOpt.get());
        return memberOpt.map(m -> allowed.contains(m.getRole())).orElse(false);
    }

    public static boolean isGlobalAdmin(User user) {
        return user != null
                && user.getRole() != null
                && user.getRole().getName() == RoleEnum.ADMIN;
    }
}
