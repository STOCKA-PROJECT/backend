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

    @Mock private OrganizationRepository organizationRepository;
    @Mock private OrganizationMemberRepository memberRepository;

    @InjectMocks private OrganizationSecurity sut;

    private Organization org;
    private User userPrincipal;
    private User adminPrincipal;

    @BeforeEach
    void setUp() {
        org = new Organization().setId(1).setName("Acme").setSlug("acme");
        userPrincipal = new User().setId(10).setEmail("u@test.com")
                .setRole(new Role().setName(RoleEnum.USER));
        adminPrincipal = new User().setId(11).setEmail("a@test.com")
                .setRole(new Role().setName(RoleEnum.ADMIN));
    }

    private void mockMembership(OrganizationRoleEnum role) {
        when(organizationRepository.findById(1)).thenReturn(Optional.of(org));
        when(memberRepository.findByUserAndOrganization(userPrincipal, org))
                .thenReturn(Optional.of(new OrganizationMember().setRole(role)));
    }

    @Nested
    @DisplayName("isMember")
    class IsMember {

        @Test
        @DisplayName("should return false when principal is null")
        void should_returnFalse_when_principalNull() {
            assertFalse(sut.isMember(1, null));
        }

        @Test
        @DisplayName("should return false when orgId is null")
        void should_returnFalse_when_orgIdNull() {
            assertFalse(sut.isMember(null, userPrincipal));
        }

        @Test
        @DisplayName("should return true when principal is global ADMIN and org exists")
        void should_returnTrue_when_globalAdmin() {
            when(organizationRepository.findById(1)).thenReturn(Optional.of(org));
            assertTrue(sut.isMember(1, adminPrincipal));
        }

        @Test
        @DisplayName("should return false when global ADMIN but org does not exist")
        void should_returnFalse_when_adminButOrgMissing() {
            when(organizationRepository.findById(1)).thenReturn(Optional.empty());
            assertFalse(sut.isMember(1, adminPrincipal));
        }

        @Test
        @DisplayName("should return true for any member role")
        void should_returnTrue_for_anyMemberRole() {
            when(organizationRepository.findById(1)).thenReturn(Optional.of(org));
            when(memberRepository.findByUserAndOrganization(userPrincipal, org))
                    .thenReturn(Optional.of(new OrganizationMember().setRole(OrganizationRoleEnum.USER)));
            assertTrue(sut.isMember(1, userPrincipal));
        }

        @Test
        @DisplayName("should return false when not a member")
        void should_returnFalse_when_notMember() {
            when(organizationRepository.findById(1)).thenReturn(Optional.of(org));
            when(memberRepository.findByUserAndOrganization(any(), any())).thenReturn(Optional.empty());
            assertFalse(sut.isMember(1, userPrincipal));
        }
    }

    @Nested
    @DisplayName("isOwner")
    class IsOwner {

        @Test
        @DisplayName("should return true for global ADMIN")
        void should_returnTrue_for_globalAdmin() {
            when(organizationRepository.findById(1)).thenReturn(Optional.of(org));
            assertTrue(sut.isOwner(1, adminPrincipal));
        }

        @Test
        @DisplayName("should return true for OWNER")
        void should_returnTrue_for_owner() {
            when(organizationRepository.findById(1)).thenReturn(Optional.of(org));
            when(memberRepository.findByUserAndOrganization(userPrincipal, org))
                    .thenReturn(Optional.of(new OrganizationMember().setRole(OrganizationRoleEnum.OWNER)));
            assertTrue(sut.isOwner(1, userPrincipal));
        }

        @Test
        @DisplayName("should return false for MANAGER")
        void should_returnFalse_for_manager() {
            when(organizationRepository.findById(1)).thenReturn(Optional.of(org));
            when(memberRepository.findByUserAndOrganization(userPrincipal, org))
                    .thenReturn(Optional.of(new OrganizationMember().setRole(OrganizationRoleEnum.MANAGER)));
            assertFalse(sut.isOwner(1, userPrincipal));
        }

        @Test
        @DisplayName("should return false for USER")
        void should_returnFalse_for_user() {
            when(organizationRepository.findById(1)).thenReturn(Optional.of(org));
            when(memberRepository.findByUserAndOrganization(userPrincipal, org))
                    .thenReturn(Optional.of(new OrganizationMember().setRole(OrganizationRoleEnum.USER)));
            assertFalse(sut.isOwner(1, userPrincipal));
        }
    }

    @Nested
    @DisplayName("isOwnerOrManager")
    class IsOwnerOrManager {

        @Test
        @DisplayName("should return true for OWNER")
        void should_returnTrue_for_owner() {
            when(organizationRepository.findById(1)).thenReturn(Optional.of(org));
            when(memberRepository.findByUserAndOrganization(userPrincipal, org))
                    .thenReturn(Optional.of(new OrganizationMember().setRole(OrganizationRoleEnum.OWNER)));
            assertTrue(sut.isOwnerOrManager(1, userPrincipal));
        }

        @Test
        @DisplayName("should return true for MANAGER")
        void should_returnTrue_for_manager() {
            when(organizationRepository.findById(1)).thenReturn(Optional.of(org));
            when(memberRepository.findByUserAndOrganization(userPrincipal, org))
                    .thenReturn(Optional.of(new OrganizationMember().setRole(OrganizationRoleEnum.MANAGER)));
            assertTrue(sut.isOwnerOrManager(1, userPrincipal));
        }

        @Test
        @DisplayName("should return false for USER")
        void should_returnFalse_for_user() {
            when(organizationRepository.findById(1)).thenReturn(Optional.of(org));
            when(memberRepository.findByUserAndOrganization(userPrincipal, org))
                    .thenReturn(Optional.of(new OrganizationMember().setRole(OrganizationRoleEnum.USER)));
            assertFalse(sut.isOwnerOrManager(1, userPrincipal));
        }

        @Test
        @DisplayName("should return false for SPECTATOR")
        void should_returnFalse_for_spectator() {
            mockMembership(OrganizationRoleEnum.SPECTATOR);
            assertFalse(sut.isOwnerOrManager(1, userPrincipal));
        }
    }

    @Nested
    @DisplayName("isSpectator")
    class IsSpectator {

        @Test
        @DisplayName("should return true only for SPECTATOR")
        void should_returnTrue_only_for_spectator() {
            mockMembership(OrganizationRoleEnum.SPECTATOR);
            assertTrue(sut.isSpectator(1, userPrincipal));
        }

        @Test
        @DisplayName("should return false for OWNER")
        void should_returnFalse_for_owner() {
            mockMembership(OrganizationRoleEnum.OWNER);
            assertFalse(sut.isSpectator(1, userPrincipal));
        }

        @Test
        @DisplayName("should return false for non-member")
        void should_returnFalse_for_nonMember() {
            when(organizationRepository.findById(1)).thenReturn(Optional.of(org));
            when(memberRepository.findByUserAndOrganization(any(), any())).thenReturn(Optional.empty());
            assertFalse(sut.isSpectator(1, userPrincipal));
        }
    }

    @Nested
    @DisplayName("canReadOrgContent")
    class CanReadOrgContent {

        @Test
        @DisplayName("should return true for SPECTATOR")
        void should_returnTrue_for_spectator() {
            mockMembership(OrganizationRoleEnum.SPECTATOR);
            assertTrue(sut.canReadOrgContent(1, userPrincipal));
        }

        @Test
        @DisplayName("should return true for OWNER, MANAGER, USER and SPECTATOR")
        void should_returnTrue_for_anyMember() {
            for (OrganizationRoleEnum role : OrganizationRoleEnum.values()) {
                mockMembership(role);
                assertTrue(sut.canReadOrgContent(1, userPrincipal),
                        "expected true for role " + role);
            }
        }

        @Test
        @DisplayName("should return false for non-member")
        void should_returnFalse_for_nonMember() {
            when(organizationRepository.findById(1)).thenReturn(Optional.of(org));
            when(memberRepository.findByUserAndOrganization(any(), any())).thenReturn(Optional.empty());
            assertFalse(sut.canReadOrgContent(1, userPrincipal));
        }

        @Test
        @DisplayName("should return true for global ADMIN")
        void should_returnTrue_for_globalAdmin() {
            when(organizationRepository.findById(1)).thenReturn(Optional.of(org));
            assertTrue(sut.canReadOrgContent(1, adminPrincipal));
        }
    }

    @Nested
    @DisplayName("canWritePieces")
    class CanWritePieces {

        @Test
        @DisplayName("should return true for OWNER")
        void should_returnTrue_for_owner() {
            mockMembership(OrganizationRoleEnum.OWNER);
            assertTrue(sut.canWritePieces(1, userPrincipal));
        }

        @Test
        @DisplayName("should return true for MANAGER")
        void should_returnTrue_for_manager() {
            mockMembership(OrganizationRoleEnum.MANAGER);
            assertTrue(sut.canWritePieces(1, userPrincipal));
        }

        @Test
        @DisplayName("should return true for USER")
        void should_returnTrue_for_user() {
            mockMembership(OrganizationRoleEnum.USER);
            assertTrue(sut.canWritePieces(1, userPrincipal));
        }

        @Test
        @DisplayName("should return false for SPECTATOR")
        void should_returnFalse_for_spectator() {
            mockMembership(OrganizationRoleEnum.SPECTATOR);
            assertFalse(sut.canWritePieces(1, userPrincipal));
        }

        @Test
        @DisplayName("should return false for non-member")
        void should_returnFalse_for_nonMember() {
            when(organizationRepository.findById(1)).thenReturn(Optional.of(org));
            when(memberRepository.findByUserAndOrganization(any(), any())).thenReturn(Optional.empty());
            assertFalse(sut.canWritePieces(1, userPrincipal));
        }
    }

    @Nested
    @DisplayName("canManageOrgContent")
    class CanManageOrgContent {

        @Test
        @DisplayName("should return true for OWNER")
        void should_returnTrue_for_owner() {
            mockMembership(OrganizationRoleEnum.OWNER);
            assertTrue(sut.canManageOrgContent(1, userPrincipal));
        }

        @Test
        @DisplayName("should return true for MANAGER")
        void should_returnTrue_for_manager() {
            mockMembership(OrganizationRoleEnum.MANAGER);
            assertTrue(sut.canManageOrgContent(1, userPrincipal));
        }

        @Test
        @DisplayName("should return false for USER")
        void should_returnFalse_for_user() {
            mockMembership(OrganizationRoleEnum.USER);
            assertFalse(sut.canManageOrgContent(1, userPrincipal));
        }

        @Test
        @DisplayName("should return false for SPECTATOR")
        void should_returnFalse_for_spectator() {
            mockMembership(OrganizationRoleEnum.SPECTATOR);
            assertFalse(sut.canManageOrgContent(1, userPrincipal));
        }
    }
}
