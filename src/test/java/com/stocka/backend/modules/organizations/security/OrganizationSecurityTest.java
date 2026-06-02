package com.stocka.backend.modules.organizations.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.organizations.entity.OrganizationMember;
import com.stocka.backend.modules.organizations.entity.OrganizationRoleEnum;
import com.stocka.backend.modules.organizations.repository.OrganizationMemberRepository;
import com.stocka.backend.modules.organizations.repository.OrganizationRepository;
import com.stocka.backend.modules.roles.entity.Role;
import com.stocka.backend.modules.roles.entity.RoleEnum;
import com.stocka.backend.modules.users.entity.User;

@ExtendWith(MockitoExtension.class)
@DisplayName("OrganizationSecurity")
class OrganizationSecurityTest {

    private static final String SLUG = "acme";

    @Mock private OrganizationRepository organizationRepository;
    @Mock private OrganizationMemberRepository memberRepository;

    @InjectMocks private OrganizationSecurity sut;

    private Organization org;
    private User userPrincipal;
    private User adminPrincipal;

    @BeforeEach
    void setUp() {
        org = new Organization().setId(1).setName("Acme").setSlug(SLUG);
        userPrincipal = new User().setId(10).setEmail("u@test.com")
                .setRole(new Role().setName(RoleEnum.USER));
        adminPrincipal = new User().setId(11).setEmail("a@test.com")
                .setRole(new Role().setName(RoleEnum.ADMIN));
    }

    private void mockMembership(OrganizationRoleEnum role) {
        when(organizationRepository.findBySlug(SLUG)).thenReturn(Optional.of(org));
        when(memberRepository.findByUserAndOrganization(userPrincipal, org))
                .thenReturn(Optional.of(new OrganizationMember().setRole(role)));
    }

    @Nested
    @DisplayName("isMember")
    class IsMember {

        @Test
        @DisplayName("should return false when principal is null")
        void should_returnFalse_when_principalNull() {
            assertFalse(sut.isMember(SLUG, null));
        }

        @Test
        @DisplayName("should return false when slug is null")
        void should_returnFalse_when_slugNull() {
            assertFalse(sut.isMember(null, userPrincipal));
        }

        @Test
        @DisplayName("should return false when slug is blank")
        void should_returnFalse_when_slugBlank() {
            assertFalse(sut.isMember("  ", userPrincipal));
        }

        @Test
        @DisplayName("should return true when principal is global ADMIN and org exists")
        void should_returnTrue_when_globalAdmin() {
            when(organizationRepository.findBySlug(SLUG)).thenReturn(Optional.of(org));
            assertTrue(sut.isMember(SLUG, adminPrincipal));
        }

        @Test
        @DisplayName("should return false when global ADMIN but org does not exist")
        void should_returnFalse_when_adminButOrgMissing() {
            when(organizationRepository.findBySlug(SLUG)).thenReturn(Optional.empty());
            assertFalse(sut.isMember(SLUG, adminPrincipal));
        }

        @Test
        @DisplayName("should return true for any member role")
        void should_returnTrue_for_anyMemberRole() {
            when(organizationRepository.findBySlug(SLUG)).thenReturn(Optional.of(org));
            when(memberRepository.findByUserAndOrganization(userPrincipal, org))
                    .thenReturn(Optional.of(new OrganizationMember().setRole(OrganizationRoleEnum.USER)));
            assertTrue(sut.isMember(SLUG, userPrincipal));
        }

        @Test
        @DisplayName("should return false when not a member")
        void should_returnFalse_when_notMember() {
            when(organizationRepository.findBySlug(SLUG)).thenReturn(Optional.of(org));
            when(memberRepository.findByUserAndOrganization(any(), any())).thenReturn(Optional.empty());
            assertFalse(sut.isMember(SLUG, userPrincipal));
        }
    }

    @Nested
    @DisplayName("isOwner")
    class IsOwner {

        @Test
        @DisplayName("should return true for global ADMIN")
        void should_returnTrue_for_globalAdmin() {
            when(organizationRepository.findBySlug(SLUG)).thenReturn(Optional.of(org));
            assertTrue(sut.isOwner(SLUG, adminPrincipal));
        }

        @Test
        @DisplayName("should return true for OWNER")
        void should_returnTrue_for_owner() {
            mockMembership(OrganizationRoleEnum.OWNER);
            assertTrue(sut.isOwner(SLUG, userPrincipal));
        }

        @Test
        @DisplayName("should return false for MANAGER")
        void should_returnFalse_for_manager() {
            mockMembership(OrganizationRoleEnum.MANAGER);
            assertFalse(sut.isOwner(SLUG, userPrincipal));
        }

        @Test
        @DisplayName("should return false for USER")
        void should_returnFalse_for_user() {
            mockMembership(OrganizationRoleEnum.USER);
            assertFalse(sut.isOwner(SLUG, userPrincipal));
        }
    }

    @Nested
    @DisplayName("isOwnerOrManager")
    class IsOwnerOrManager {

        @Test
        @DisplayName("should return true for OWNER")
        void should_returnTrue_for_owner() {
            mockMembership(OrganizationRoleEnum.OWNER);
            assertTrue(sut.isOwnerOrManager(SLUG, userPrincipal));
        }

        @Test
        @DisplayName("should return true for MANAGER")
        void should_returnTrue_for_manager() {
            mockMembership(OrganizationRoleEnum.MANAGER);
            assertTrue(sut.isOwnerOrManager(SLUG, userPrincipal));
        }

        @Test
        @DisplayName("should return false for USER")
        void should_returnFalse_for_user() {
            mockMembership(OrganizationRoleEnum.USER);
            assertFalse(sut.isOwnerOrManager(SLUG, userPrincipal));
        }

        @Test
        @DisplayName("should return false for SPECTATOR")
        void should_returnFalse_for_spectator() {
            mockMembership(OrganizationRoleEnum.SPECTATOR);
            assertFalse(sut.isOwnerOrManager(SLUG, userPrincipal));
        }
    }

    @Nested
    @DisplayName("isSpectator")
    class IsSpectator {

        @Test
        @DisplayName("should return true only for SPECTATOR")
        void should_returnTrue_only_for_spectator() {
            mockMembership(OrganizationRoleEnum.SPECTATOR);
            assertTrue(sut.isSpectator(SLUG, userPrincipal));
        }

        @Test
        @DisplayName("should return false for OWNER")
        void should_returnFalse_for_owner() {
            mockMembership(OrganizationRoleEnum.OWNER);
            assertFalse(sut.isSpectator(SLUG, userPrincipal));
        }

        @Test
        @DisplayName("should return false for non-member")
        void should_returnFalse_for_nonMember() {
            when(organizationRepository.findBySlug(SLUG)).thenReturn(Optional.of(org));
            when(memberRepository.findByUserAndOrganization(any(), any())).thenReturn(Optional.empty());
            assertFalse(sut.isSpectator(SLUG, userPrincipal));
        }
    }

    @Nested
    @DisplayName("canReadOrgContent")
    class CanReadOrgContent {

        @Test
        @DisplayName("should return true for SPECTATOR")
        void should_returnTrue_for_spectator() {
            mockMembership(OrganizationRoleEnum.SPECTATOR);
            assertTrue(sut.canReadOrgContent(SLUG, userPrincipal));
        }

        @Test
        @DisplayName("should return true for OWNER, MANAGER, USER and SPECTATOR")
        void should_returnTrue_for_anyMember() {
            for (OrganizationRoleEnum role : OrganizationRoleEnum.values()) {
                mockMembership(role);
                assertTrue(sut.canReadOrgContent(SLUG, userPrincipal),
                        "expected true for role " + role);
            }
        }

        @Test
        @DisplayName("should return false for non-member")
        void should_returnFalse_for_nonMember() {
            when(organizationRepository.findBySlug(SLUG)).thenReturn(Optional.of(org));
            when(memberRepository.findByUserAndOrganization(any(), any())).thenReturn(Optional.empty());
            assertFalse(sut.canReadOrgContent(SLUG, userPrincipal));
        }

        @Test
        @DisplayName("should return true for global ADMIN")
        void should_returnTrue_for_globalAdmin() {
            when(organizationRepository.findBySlug(SLUG)).thenReturn(Optional.of(org));
            assertTrue(sut.canReadOrgContent(SLUG, adminPrincipal));
        }
    }

    @Nested
    @DisplayName("canWritePieces")
    class CanWritePieces {

        @Test
        @DisplayName("should return true for OWNER")
        void should_returnTrue_for_owner() {
            mockMembership(OrganizationRoleEnum.OWNER);
            assertTrue(sut.canWritePieces(SLUG, userPrincipal));
        }

        @Test
        @DisplayName("should return true for MANAGER")
        void should_returnTrue_for_manager() {
            mockMembership(OrganizationRoleEnum.MANAGER);
            assertTrue(sut.canWritePieces(SLUG, userPrincipal));
        }

        @Test
        @DisplayName("should return true for USER")
        void should_returnTrue_for_user() {
            mockMembership(OrganizationRoleEnum.USER);
            assertTrue(sut.canWritePieces(SLUG, userPrincipal));
        }

        @Test
        @DisplayName("should return false for SPECTATOR")
        void should_returnFalse_for_spectator() {
            mockMembership(OrganizationRoleEnum.SPECTATOR);
            assertFalse(sut.canWritePieces(SLUG, userPrincipal));
        }

        @Test
        @DisplayName("should return false for non-member")
        void should_returnFalse_for_nonMember() {
            when(organizationRepository.findBySlug(SLUG)).thenReturn(Optional.of(org));
            when(memberRepository.findByUserAndOrganization(any(), any())).thenReturn(Optional.empty());
            assertFalse(sut.canWritePieces(SLUG, userPrincipal));
        }
    }

    @Nested
    @DisplayName("canManageOrgContent")
    class CanManageOrgContent {

        @Test
        @DisplayName("should return true for OWNER")
        void should_returnTrue_for_owner() {
            mockMembership(OrganizationRoleEnum.OWNER);
            assertTrue(sut.canManageOrgContent(SLUG, userPrincipal));
        }

        @Test
        @DisplayName("should return true for MANAGER")
        void should_returnTrue_for_manager() {
            mockMembership(OrganizationRoleEnum.MANAGER);
            assertTrue(sut.canManageOrgContent(SLUG, userPrincipal));
        }

        @Test
        @DisplayName("should return false for USER")
        void should_returnFalse_for_user() {
            mockMembership(OrganizationRoleEnum.USER);
            assertFalse(sut.canManageOrgContent(SLUG, userPrincipal));
        }

        @Test
        @DisplayName("should return false for SPECTATOR")
        void should_returnFalse_for_spectator() {
            mockMembership(OrganizationRoleEnum.SPECTATOR);
            assertFalse(sut.canManageOrgContent(SLUG, userPrincipal));
        }
    }

    private void mockAdminOwnedMembership(OrganizationRoleEnum role) {
        when(organizationRepository.findBySlug(SLUG)).thenReturn(Optional.of(org));
        when(memberRepository.existsAdminOwner(org, RoleEnum.ADMIN)).thenReturn(true);
        when(memberRepository.findByUserAndOrganization(userPrincipal, org))
                .thenReturn(Optional.of(new OrganizationMember().setRole(role)));
    }

    @Nested
    @DisplayName("canReadPieceTypeActions")
    class CanReadPieceTypeActions {

        @Test
        @DisplayName("should return true for global ADMIN without touching the org")
        void should_returnTrue_for_globalAdmin() {
            assertTrue(sut.canReadPieceTypeActions(SLUG, adminPrincipal));
        }

        @Test
        @DisplayName("should return true for any member of an admin-owned org")
        void should_returnTrue_for_memberOfAdminOwnedOrg() {
            for (OrganizationRoleEnum role : OrganizationRoleEnum.values()) {
                mockAdminOwnedMembership(role);
                assertTrue(sut.canReadPieceTypeActions(SLUG, userPrincipal),
                        "expected true for role " + role);
            }
        }

        @Test
        @DisplayName("should return false when the org owner is not a global admin")
        void should_returnFalse_when_ownerNotAdmin() {
            when(organizationRepository.findBySlug(SLUG)).thenReturn(Optional.of(org));
            when(memberRepository.existsAdminOwner(org, RoleEnum.ADMIN)).thenReturn(false);
            assertFalse(sut.canReadPieceTypeActions(SLUG, userPrincipal));
        }

        @Test
        @DisplayName("should return false for a non-member of an admin-owned org")
        void should_returnFalse_for_nonMember() {
            when(organizationRepository.findBySlug(SLUG)).thenReturn(Optional.of(org));
            when(memberRepository.existsAdminOwner(org, RoleEnum.ADMIN)).thenReturn(true);
            when(memberRepository.findByUserAndOrganization(userPrincipal, org)).thenReturn(Optional.empty());
            assertFalse(sut.canReadPieceTypeActions(SLUG, userPrincipal));
        }

        @Test
        @DisplayName("should return false when the org does not exist")
        void should_returnFalse_when_orgMissing() {
            when(organizationRepository.findBySlug(SLUG)).thenReturn(Optional.empty());
            assertFalse(sut.canReadPieceTypeActions(SLUG, userPrincipal));
        }

        @Test
        @DisplayName("should return false when principal is not a user")
        void should_returnFalse_when_principalNull() {
            assertFalse(sut.canReadPieceTypeActions(SLUG, null));
        }
    }

    @Nested
    @DisplayName("canManagePieceTypeActions")
    class CanManagePieceTypeActions {

        @Test
        @DisplayName("should return true for global ADMIN")
        void should_returnTrue_for_globalAdmin() {
            assertTrue(sut.canManagePieceTypeActions(SLUG, adminPrincipal));
        }

        @Test
        @DisplayName("should return true for OWNER of an admin-owned org")
        void should_returnTrue_for_owner() {
            mockAdminOwnedMembership(OrganizationRoleEnum.OWNER);
            assertTrue(sut.canManagePieceTypeActions(SLUG, userPrincipal));
        }

        @Test
        @DisplayName("should return true for MANAGER of an admin-owned org")
        void should_returnTrue_for_manager() {
            mockAdminOwnedMembership(OrganizationRoleEnum.MANAGER);
            assertTrue(sut.canManagePieceTypeActions(SLUG, userPrincipal));
        }

        @Test
        @DisplayName("should return false for USER of an admin-owned org")
        void should_returnFalse_for_user() {
            mockAdminOwnedMembership(OrganizationRoleEnum.USER);
            assertFalse(sut.canManagePieceTypeActions(SLUG, userPrincipal));
        }

        @Test
        @DisplayName("should return false for SPECTATOR of an admin-owned org")
        void should_returnFalse_for_spectator() {
            mockAdminOwnedMembership(OrganizationRoleEnum.SPECTATOR);
            assertFalse(sut.canManagePieceTypeActions(SLUG, userPrincipal));
        }

        @Test
        @DisplayName("should return false for MANAGER when the org owner is not a global admin")
        void should_returnFalse_when_ownerNotAdmin() {
            when(organizationRepository.findBySlug(SLUG)).thenReturn(Optional.of(org));
            when(memberRepository.existsAdminOwner(org, RoleEnum.ADMIN)).thenReturn(false);
            assertFalse(sut.canManagePieceTypeActions(SLUG, userPrincipal));
        }
    }

    @Nested
    @DisplayName("pieceTypeActionsEnabled")
    class PieceTypeActionsEnabled {

        @Test
        @DisplayName("should return true for a global ADMIN regardless of the org")
        void should_returnTrue_for_globalAdmin() {
            assertTrue(sut.pieceTypeActionsEnabled(org, adminPrincipal));
        }

        @Test
        @DisplayName("should return true when the org owner is a global admin")
        void should_returnTrue_when_ownerAdmin() {
            when(memberRepository.existsAdminOwner(org, RoleEnum.ADMIN)).thenReturn(true);
            assertTrue(sut.pieceTypeActionsEnabled(org, userPrincipal));
        }

        @Test
        @DisplayName("should return false when the org owner is not a global admin")
        void should_returnFalse_when_ownerNotAdmin() {
            when(memberRepository.existsAdminOwner(org, RoleEnum.ADMIN)).thenReturn(false);
            assertFalse(sut.pieceTypeActionsEnabled(org, userPrincipal));
        }

        @Test
        @DisplayName("should return false when the org is null and the user is not admin")
        void should_returnFalse_when_orgNull() {
            assertFalse(sut.pieceTypeActionsEnabled(null, userPrincipal));
        }
    }
}
