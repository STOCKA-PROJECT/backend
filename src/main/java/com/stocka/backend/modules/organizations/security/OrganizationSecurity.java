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
 * {@code @PreAuthorize("@orgSecurity.canWritePieces(#orgSlug, principal)")}. The slug must
 * be the current one; historical slugs are not honoured for authorization, since the
 * deep-link redirect happens client-side via {@code GET /organizations/by-slug/{slug}}.
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

    public boolean isMember(String orgSlug, Object principal) {
        return hasAnyRole(orgSlug, principal, Set.of(
                OrganizationRoleEnum.OWNER,
                OrganizationRoleEnum.MANAGER,
                OrganizationRoleEnum.USER,
                OrganizationRoleEnum.SPECTATOR
        ));
    }

    public boolean isOwner(String orgSlug, Object principal) {
        return hasAnyRole(orgSlug, principal, Set.of(OrganizationRoleEnum.OWNER));
    }

    public boolean isOwnerOrManager(String orgSlug, Object principal) {
        return hasAnyRole(orgSlug, principal, Set.of(
                OrganizationRoleEnum.OWNER,
                OrganizationRoleEnum.MANAGER
        ));
    }

    public boolean isSpectator(String orgSlug, Object principal) {
        return hasAnyRole(orgSlug, principal, Set.of(OrganizationRoleEnum.SPECTATOR));
    }

    /**
     * Anyone in the organization (including SPECTATOR) can read its content.
     */
    public boolean canReadOrgContent(String orgSlug, Object principal) {
        return isMember(orgSlug, principal);
    }

    /**
     * OWNER, MANAGER and USER can create/modify/delete pieces and their attachments.
     * SPECTATOR cannot.
     */
    public boolean canWritePieces(String orgSlug, Object principal) {
        return hasAnyRole(orgSlug, principal, Set.of(
                OrganizationRoleEnum.OWNER,
                OrganizationRoleEnum.MANAGER,
                OrganizationRoleEnum.USER
        ));
    }

    /**
     * Only OWNER and MANAGER can create/modify/delete locations and piece types.
     */
    public boolean canManageOrgContent(String orgSlug, Object principal) {
        return isOwnerOrManager(orgSlug, principal);
    }

    private boolean hasAnyRole(String orgSlug, Object principal, Set<OrganizationRoleEnum> allowed) {
        if (orgSlug == null || orgSlug.isBlank()) {
            return false;
        }
        if (!(principal instanceof User user)) {
            return false;
        }
        Optional<Organization> orgOpt = organizationRepository.findBySlug(orgSlug);
        if (orgOpt.isEmpty()) {
            return false;
        }
        if (isGlobalAdmin(user)) {
            return true;
        }
        Optional<OrganizationMember> memberOpt = memberRepository.findByUserAndOrganization(user, orgOpt.get());
        return memberOpt.map(m -> allowed.contains(m.getRole())).orElse(false);
    }

    public static boolean isGlobalAdmin(User user) {
        return user != null
                && user.getRole() != null
                && user.getRole().getName() == RoleEnum.ADMIN;
    }
}
