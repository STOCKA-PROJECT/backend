package com.stocka.backend.modules.organizations.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.stocka.backend.modules.organizations.entity.AuditAction;
import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.organizations.entity.OrganizationMember;
import com.stocka.backend.modules.organizations.entity.OrganizationRoleEnum;
import com.stocka.backend.modules.organizations.repository.OrganizationMemberRepository;
import com.stocka.backend.modules.roles.entity.Role;
import com.stocka.backend.modules.roles.entity.RoleEnum;
import com.stocka.backend.modules.users.entity.User;

@ExtendWith(MockitoExtension.class)
@DisplayName("OrganizationMemberService")
class OrganizationMemberServiceTest {

    @Mock private OrganizationMemberRepository memberRepository;
    @Mock private OrganizationService organizationService;
    @Mock private OrganizationAuditService auditService;

    @InjectMocks private OrganizationMemberService sut;

    private Organization org;
    private User actor;
    private User target;

    @BeforeEach
    void setUp() {
        org = new Organization().setId(1).setName("Acme").setSlug("acme");
        actor = new User().setId(10).setEmail("actor@test.com").setRole(new Role().setName(RoleEnum.USER));
        target = new User().setId(20).setEmail("target@test.com").setRole(new Role().setName(RoleEnum.USER));
    }

    private OrganizationMember member(Integer id, User u, OrganizationRoleEnum role) {
        return new OrganizationMember().setId(id).setUser(u).setOrganization(org).setRole(role);
    }

    @Nested
    @DisplayName("listMembers")
    class ListMembers {

        @Test
        @DisplayName("should return all members of the organization")
        void should_returnAllMembers() {
            when(organizationService.findById(1)).thenReturn(org);
            List<OrganizationMember> list = List.of(member(1, actor, OrganizationRoleEnum.OWNER));
            when(memberRepository.findByOrganization(org)).thenReturn(list);

            assertSame(list, sut.listMembers(1));
        }
    }

    @Nested
    @DisplayName("updateMemberRole")
    class UpdateMemberRole {

        @Test
        @DisplayName("should update role and audit when actor is OWNER (covered by controller @PreAuthorize)")
        void should_updateRole() {
            OrganizationMember mem = member(50, target, OrganizationRoleEnum.USER);
            when(organizationService.findById(1)).thenReturn(org);
            when(memberRepository.findById(50)).thenReturn(Optional.of(mem));
            when(memberRepository.save(mem)).thenReturn(mem);

            sut.updateMemberRole(1, 50, OrganizationRoleEnum.MANAGER, actor);

            assertEquals(OrganizationRoleEnum.MANAGER, mem.getRole());
            verify(auditService).log(eq(org), eq(actor), eq(AuditAction.MEMBER_ROLE_CHANGED), eq(target), any());
        }

        @Test
        @DisplayName("should be a no-op when role does not change")
        void should_noop_when_sameRole() {
            OrganizationMember mem = member(50, target, OrganizationRoleEnum.USER);
            when(organizationService.findById(1)).thenReturn(org);
            when(memberRepository.findById(50)).thenReturn(Optional.of(mem));

            sut.updateMemberRole(1, 50, OrganizationRoleEnum.USER, actor);

            verify(memberRepository, never()).save(any());
            verify(auditService, never()).log(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("should throw 400 when newRole is null")
        void should_throw400_when_newRoleNull() {
            assertThrows(ResponseStatusException.class,
                    () -> sut.updateMemberRole(1, 50, null, actor));
        }

        @Test
        @DisplayName("should throw 404 when member belongs to a different org")
        void should_throw404_when_memberInDifferentOrg() {
            Organization other = new Organization().setId(99);
            OrganizationMember mem = member(50, target, OrganizationRoleEnum.USER).setOrganization(other);
            when(organizationService.findById(1)).thenReturn(org);
            when(memberRepository.findById(50)).thenReturn(Optional.of(mem));

            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> sut.updateMemberRole(1, 50, OrganizationRoleEnum.MANAGER, actor));
            assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        }

        @Test
        @DisplayName("should allow demoting OWNER when there are multiple OWNERs")
        void should_demoteOwner_when_multipleOwnersExist() {
            OrganizationMember mem = member(50, target, OrganizationRoleEnum.OWNER);
            when(organizationService.findById(1)).thenReturn(org);
            when(memberRepository.findById(50)).thenReturn(Optional.of(mem));
            when(memberRepository.countByOrganizationAndRole(org, OrganizationRoleEnum.OWNER)).thenReturn(2L);
            when(memberRepository.save(mem)).thenReturn(mem);

            sut.updateMemberRole(1, 50, OrganizationRoleEnum.MANAGER, actor);

            assertEquals(OrganizationRoleEnum.MANAGER, mem.getRole());
        }

        @Test
        @DisplayName("should throw 409 when demoting the last OWNER")
        void should_throw409_when_demotingLastOwner() {
            OrganizationMember mem = member(50, target, OrganizationRoleEnum.OWNER);
            when(organizationService.findById(1)).thenReturn(org);
            when(memberRepository.findById(50)).thenReturn(Optional.of(mem));
            when(memberRepository.countByOrganizationAndRole(org, OrganizationRoleEnum.OWNER)).thenReturn(1L);

            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> sut.updateMemberRole(1, 50, OrganizationRoleEnum.USER, actor));
            assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        }
    }

    @Nested
    @DisplayName("removeMember")
    class RemoveMember {

        @Test
        @DisplayName("OWNER actor should be able to remove a USER")
        void owner_canRemove_user() {
            OrganizationMember actorMem = member(1, actor, OrganizationRoleEnum.OWNER);
            OrganizationMember victim = member(50, target, OrganizationRoleEnum.USER);
            when(organizationService.findById(1)).thenReturn(org);
            when(memberRepository.findById(50)).thenReturn(Optional.of(victim));
            when(memberRepository.findByUserAndOrganization(actor, org)).thenReturn(Optional.of(actorMem));
            when(memberRepository.save(victim)).thenReturn(victim);

            sut.removeMember(1, 50, actor);

            assertNotNull(victim.getDeletedAt());
            verify(auditService).log(eq(org), eq(actor), eq(AuditAction.MEMBER_REMOVED), eq(target), any());
        }

        @Test
        @DisplayName("OWNER actor should be able to remove another MANAGER")
        void owner_canRemove_manager() {
            OrganizationMember actorMem = member(1, actor, OrganizationRoleEnum.OWNER);
            OrganizationMember victim = member(50, target, OrganizationRoleEnum.MANAGER);
            when(organizationService.findById(1)).thenReturn(org);
            when(memberRepository.findById(50)).thenReturn(Optional.of(victim));
            when(memberRepository.findByUserAndOrganization(actor, org)).thenReturn(Optional.of(actorMem));
            when(memberRepository.save(victim)).thenReturn(victim);

            sut.removeMember(1, 50, actor);

            assertNotNull(victim.getDeletedAt());
        }

        @Test
        @DisplayName("MANAGER actor should be able to remove a USER")
        void manager_canRemove_user() {
            OrganizationMember actorMem = member(1, actor, OrganizationRoleEnum.MANAGER);
            OrganizationMember victim = member(50, target, OrganizationRoleEnum.USER);
            when(organizationService.findById(1)).thenReturn(org);
            when(memberRepository.findById(50)).thenReturn(Optional.of(victim));
            when(memberRepository.findByUserAndOrganization(actor, org)).thenReturn(Optional.of(actorMem));
            when(memberRepository.save(victim)).thenReturn(victim);

            sut.removeMember(1, 50, actor);

            assertNotNull(victim.getDeletedAt());
        }

        @Test
        @DisplayName("MANAGER actor should NOT be able to remove a MANAGER")
        void manager_cannotRemove_manager() {
            OrganizationMember actorMem = member(1, actor, OrganizationRoleEnum.MANAGER);
            OrganizationMember victim = member(50, target, OrganizationRoleEnum.MANAGER);
            when(organizationService.findById(1)).thenReturn(org);
            when(memberRepository.findById(50)).thenReturn(Optional.of(victim));
            when(memberRepository.findByUserAndOrganization(actor, org)).thenReturn(Optional.of(actorMem));

            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> sut.removeMember(1, 50, actor));
            assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        }

        @Test
        @DisplayName("MANAGER actor should NOT be able to remove an OWNER")
        void manager_cannotRemove_owner() {
            OrganizationMember actorMem = member(1, actor, OrganizationRoleEnum.MANAGER);
            OrganizationMember victim = member(50, target, OrganizationRoleEnum.OWNER);
            when(organizationService.findById(1)).thenReturn(org);
            when(memberRepository.findById(50)).thenReturn(Optional.of(victim));
            when(memberRepository.findByUserAndOrganization(actor, org)).thenReturn(Optional.of(actorMem));

            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> sut.removeMember(1, 50, actor));
            assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        }

        @Test
        @DisplayName("USER actor should NOT be able to remove anyone")
        void user_cannotRemove_anyone() {
            OrganizationMember actorMem = member(1, actor, OrganizationRoleEnum.USER);
            OrganizationMember victim = member(50, target, OrganizationRoleEnum.USER);
            when(organizationService.findById(1)).thenReturn(org);
            when(memberRepository.findById(50)).thenReturn(Optional.of(victim));
            when(memberRepository.findByUserAndOrganization(actor, org)).thenReturn(Optional.of(actorMem));

            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> sut.removeMember(1, 50, actor));
            assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        }

        @Test
        @DisplayName("should throw 409 when removing the last OWNER")
        void should_throw409_when_removingLastOwner() {
            OrganizationMember actorMem = member(1, actor, OrganizationRoleEnum.OWNER);
            OrganizationMember victim = member(50, target, OrganizationRoleEnum.OWNER);
            when(organizationService.findById(1)).thenReturn(org);
            when(memberRepository.findById(50)).thenReturn(Optional.of(victim));
            when(memberRepository.findByUserAndOrganization(actor, org)).thenReturn(Optional.of(actorMem));
            when(memberRepository.countByOrganizationAndRole(org, OrganizationRoleEnum.OWNER)).thenReturn(1L);

            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> sut.removeMember(1, 50, actor));
            assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        }

        @Test
        @DisplayName("global ADMIN should be able to remove anyone")
        void admin_canRemove_anyone() {
            User admin = new User().setId(99).setRole(new Role().setName(RoleEnum.ADMIN));
            OrganizationMember victim = member(50, target, OrganizationRoleEnum.MANAGER);
            when(organizationService.findById(1)).thenReturn(org);
            when(memberRepository.findById(50)).thenReturn(Optional.of(victim));
            when(memberRepository.save(victim)).thenReturn(victim);

            sut.removeMember(1, 50, admin);

            assertNotNull(victim.getDeletedAt());
        }
    }

    @Nested
    @DisplayName("leaveOrganization")
    class LeaveOrganization {

        @Test
        @DisplayName("USER should leave successfully")
        void user_canLeave() {
            OrganizationMember mem = member(50, actor, OrganizationRoleEnum.USER);
            when(organizationService.findById(1)).thenReturn(org);
            when(memberRepository.findByUserAndOrganization(actor, org)).thenReturn(Optional.of(mem));
            when(memberRepository.save(mem)).thenReturn(mem);

            sut.leaveOrganization(1, actor);

            assertNotNull(mem.getDeletedAt());
            verify(auditService).log(eq(org), eq(actor), eq(AuditAction.MEMBER_LEFT), eq(actor), any());
        }

        @Test
        @DisplayName("OWNER should leave when there are other OWNERs")
        void owner_canLeave_when_othersExist() {
            OrganizationMember mem = member(50, actor, OrganizationRoleEnum.OWNER);
            when(organizationService.findById(1)).thenReturn(org);
            when(memberRepository.findByUserAndOrganization(actor, org)).thenReturn(Optional.of(mem));
            when(memberRepository.countByOrganizationAndRole(org, OrganizationRoleEnum.OWNER)).thenReturn(2L);
            when(memberRepository.save(mem)).thenReturn(mem);

            sut.leaveOrganization(1, actor);

            assertNotNull(mem.getDeletedAt());
        }

        @Test
        @DisplayName("last OWNER should not be able to leave")
        void lastOwner_cannotLeave() {
            OrganizationMember mem = member(50, actor, OrganizationRoleEnum.OWNER);
            when(organizationService.findById(1)).thenReturn(org);
            when(memberRepository.findByUserAndOrganization(actor, org)).thenReturn(Optional.of(mem));
            when(memberRepository.countByOrganizationAndRole(org, OrganizationRoleEnum.OWNER)).thenReturn(1L);

            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> sut.leaveOrganization(1, actor));
            assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        }

        @Test
        @DisplayName("should throw 404 when actor is not a member")
        void should_throw404_when_notMember() {
            when(organizationService.findById(1)).thenReturn(org);
            when(memberRepository.findByUserAndOrganization(actor, org)).thenReturn(Optional.empty());

            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> sut.leaveOrganization(1, actor));
            assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        }
    }
}
