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

    /**
     * Whether the caller may read the piece-type actions feature for {@code orgSlug}. This is a
     * private feature: it is only available on organizations whose owner is a global admin. Any
     * member of such an organization can read, and global admins can read anywhere.
     *
     * @param orgSlug   current slug of the organization
     * @param principal the authenticated principal ({@link User})
     * @return {@code true} when the caller may read actions
     */
    public boolean canReadPieceTypeActions(String orgSlug, Object principal) {
        if (!(principal instanceof User user)) {
            return false;
        }
        if (isGlobalAdmin(user)) {
            return true;
        }
        Optional<Organization> orgOpt = lookupActionsOrg(orgSlug);
        if (orgOpt.isEmpty() || !orgOwnerIsGlobalAdmin(orgOpt.get())) {
            return false;
        }
        return memberRepository.findByUserAndOrganization(user, orgOpt.get()).isPresent();
    }

    /**
     * Whether the caller may create/modify/delete piece-type actions for {@code orgSlug}. Requires
     * OWNER or MANAGER on an organization whose owner is a global admin; global admins may always.
     *
     * @param orgSlug   current slug of the organization
     * @param principal the authenticated principal ({@link User})
     * @return {@code true} when the caller may manage actions
     */
    public boolean canManagePieceTypeActions(String orgSlug, Object principal) {
        if (!(principal instanceof User user)) {
            return false;
        }
        if (isGlobalAdmin(user)) {
            return true;
        }
        Optional<Organization> orgOpt = lookupActionsOrg(orgSlug);
        if (orgOpt.isEmpty() || !orgOwnerIsGlobalAdmin(orgOpt.get())) {
            return false;
        }
        return memberRepository.findByUserAndOrganization(user, orgOpt.get())
                .map(m -> m.getRole() == OrganizationRoleEnum.OWNER
                        || m.getRole() == OrganizationRoleEnum.MANAGER)
                .orElse(false);
    }

    /**
     * Whether the piece-type actions feature is enabled for {@code org} as seen by {@code user}.
     * Used to expose a capability flag on the organization response so the frontend can show or
     * hide the feature without probing the endpoint. Mirrors {@link #canReadPieceTypeActions} for a
     * caller already known to be a member.
     *
     * @param org  organization to inspect (may be {@code null})
     * @param user authenticated user (may be {@code null})
     * @return {@code true} when actions should be exposed to {@code user} for {@code org}
     */
    public boolean pieceTypeActionsEnabled(Organization org, User user) {
        if (isGlobalAdmin(user)) {
            return true;
        }
        return org != null && orgOwnerIsGlobalAdmin(org);
    }

    /**
     * Whether the caller may read the ports feature for {@code orgSlug}. Like the piece-type actions
     * feature, this is a private feature: it is only available on organizations whose owner is a
     * global admin. Any member of such an organization can read, and global admins can read anywhere.
     *
     * @param orgSlug   current slug of the organization
     * @param principal the authenticated principal ({@link User})
     * @return {@code true} when the caller may read ports
     */
    public boolean canReadPorts(String orgSlug, Object principal) {
        if (!(principal instanceof User user)) {
            return false;
        }
        if (isGlobalAdmin(user)) {
            return true;
        }
        Optional<Organization> orgOpt = lookupActionsOrg(orgSlug);
        if (orgOpt.isEmpty() || !orgOwnerIsGlobalAdmin(orgOpt.get())) {
            return false;
        }
        return memberRepository.findByUserAndOrganization(user, orgOpt.get()).isPresent();
    }

    /**
     * Whether the caller may create/modify/delete ports for {@code orgSlug}. Requires OWNER or
     * MANAGER on an organization whose owner is a global admin; global admins may always.
     *
     * @param orgSlug   current slug of the organization
     * @param principal the authenticated principal ({@link User})
     * @return {@code true} when the caller may manage ports
     */
    public boolean canManagePorts(String orgSlug, Object principal) {
        if (!(principal instanceof User user)) {
            return false;
        }
        if (isGlobalAdmin(user)) {
            return true;
        }
        Optional<Organization> orgOpt = lookupActionsOrg(orgSlug);
        if (orgOpt.isEmpty() || !orgOwnerIsGlobalAdmin(orgOpt.get())) {
            return false;
        }
        return memberRepository.findByUserAndOrganization(user, orgOpt.get())
                .map(m -> m.getRole() == OrganizationRoleEnum.OWNER
                        || m.getRole() == OrganizationRoleEnum.MANAGER)
                .orElse(false);
    }

    /**
     * Whether the ports feature is enabled for {@code org} as seen by {@code user}. Used to expose a
     * capability flag on the organization response so the frontend can show or hide the feature
     * without probing the endpoint. Mirrors {@link #canReadPorts} for a caller already known to be a
     * member.
     *
     * @param org  organization to inspect (may be {@code null})
     * @param user authenticated user (may be {@code null})
     * @return {@code true} when ports should be exposed to {@code user} for {@code org}
     */
    public boolean portsEnabled(Organization org, User user) {
        if (isGlobalAdmin(user)) {
            return true;
        }
        return org != null && orgOwnerIsGlobalAdmin(org);
    }

    private Optional<Organization> lookupActionsOrg(String orgSlug) {
        if (orgSlug == null || orgSlug.isBlank()) {
            return Optional.empty();
        }
        return organizationRepository.findBySlug(orgSlug);
    }

    private boolean orgOwnerIsGlobalAdmin(Organization org) {
        return memberRepository.existsAdminOwner(org, RoleEnum.ADMIN);
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
