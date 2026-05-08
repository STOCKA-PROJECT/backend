package com.stocka.backend.modules.organizations.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import org.mockito.Mock;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.stocka.backend.modules.common.error.ApiException;
import com.stocka.backend.modules.common.error.ErrorCodes;
import com.stocka.backend.modules.notifications.email.EmailService;
import com.stocka.backend.modules.organizations.dto.CreateInvitationDto;
import com.stocka.backend.modules.organizations.entity.AuditAction;
import com.stocka.backend.modules.organizations.entity.InvitationStatus;
import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.organizations.entity.OrganizationInvitation;
import com.stocka.backend.modules.organizations.entity.OrganizationMember;
import com.stocka.backend.modules.organizations.entity.OrganizationRoleEnum;
import com.stocka.backend.modules.organizations.repository.OrganizationInvitationRepository;
import com.stocka.backend.modules.organizations.repository.OrganizationMemberRepository;
import com.stocka.backend.modules.roles.entity.Role;
import com.stocka.backend.modules.roles.entity.RoleEnum;
import com.stocka.backend.modules.users.entity.Language;
import com.stocka.backend.modules.users.entity.User;
import com.stocka.backend.modules.users.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("OrganizationInvitationService")
class OrganizationInvitationServiceTest {

        @Mock
        private OrganizationInvitationRepository invitationRepository;
        @Mock
        private OrganizationMemberRepository memberRepository;
        @Mock
        private OrganizationService organizationService;
        @Mock
        private OrganizationAuditService auditService;
        @Mock
        private EmailService emailService;
        @Mock
        private UserRepository userRepository;

        private OrganizationInvitationService sut;

        private Organization org;
        private User owner;
        private User manager;
        private User regularUser;
        private User invitee;

        @BeforeEach
        void setUp() {
                sut = new OrganizationInvitationService(
                                invitationRepository, memberRepository, organizationService, auditService,
                                emailService, userRepository, 50L, "http://localhost:3002");
                org = new Organization().setId(1).setName("Acme").setSlug("acme");
                owner = new User().setId(10).setEmail("owner@test.com").setName("Owner")
                                .setRole(new Role().setName(RoleEnum.USER));
                manager = new User().setId(11).setEmail("mgr@test.com").setName("Mgr")
                                .setRole(new Role().setName(RoleEnum.USER));
                regularUser = new User().setId(12).setEmail("user@test.com").setName("U")
                                .setRole(new Role().setName(RoleEnum.USER));
                invitee = new User().setId(20).setEmail("invitee@test.com");
        }

        private void mockActorAs(User actor, OrganizationRoleEnum role) {
                OrganizationMember mem = new OrganizationMember().setUser(actor).setOrganization(org).setRole(role);
                when(memberRepository.findByUserAndOrganization(actor, org)).thenReturn(Optional.of(mem));
        }

        @Nested
        @DisplayName("createInvitation")
        class CreateInvitation {

                @Test
                @DisplayName("OWNER should be able to invite with USER, MANAGER and OWNER roles")
                void owner_canInviteWithAnyRole() {
                        for (OrganizationRoleEnum role : OrganizationRoleEnum.values()) {
                                when(organizationService.findById(1)).thenReturn(org);
                                mockActorAs(owner, OrganizationRoleEnum.OWNER);
                                when(memberRepository.findByOrganization(org)).thenReturn(List.of());
                                when(invitationRepository.findByOrganizationAndEmailAndStatus(any(), any(), any()))
                                                .thenReturn(Optional.empty());
                                when(invitationRepository.save(any(OrganizationInvitation.class)))
                                                .thenAnswer(i -> i.getArgument(0));

                                CreateInvitationDto dto = new CreateInvitationDto().setEmail("x" + role + "@test.com")
                                                .setRole(role);
                                OrganizationInvitation inv = sut.createInvitation(1, dto, owner);

                                assertEquals(role, inv.getRole());
                                assertEquals(InvitationStatus.PENDING, inv.getStatus());
                                assertNotNull(inv.getToken());
                                assertNotNull(inv.getExpiresAt());
                                assertEquals(owner, inv.getInvitedBy());
                        }
                        verify(emailService, atLeast(3))
                                        .sendInvitationEmail(anyString(), anyString(), anyString(), anyString(),
                                                        any(Language.class));
                        verify(auditService, atLeast(3))
                                        .log(eq(org), eq(owner), eq(AuditAction.MEMBER_INVITED), eq(null), any());
                }

                @Test
                @DisplayName("MANAGER should be able to invite with USER role")
                void manager_canInvite_user() {
                        when(organizationService.findById(1)).thenReturn(org);
                        mockActorAs(manager, OrganizationRoleEnum.MANAGER);
                        when(memberRepository.findByOrganization(org)).thenReturn(List.of());
                        when(invitationRepository.findByOrganizationAndEmailAndStatus(any(), any(), any()))
                                        .thenReturn(Optional.empty());
                        when(invitationRepository.save(any(OrganizationInvitation.class)))
                                        .thenAnswer(i -> i.getArgument(0));

                        sut.createInvitation(1,
                                        new CreateInvitationDto().setEmail("x@test.com")
                                                        .setRole(OrganizationRoleEnum.USER),
                                        manager);

                        verify(invitationRepository).save(any(OrganizationInvitation.class));
                }

                @Test
                @DisplayName("MANAGER should NOT be able to invite with MANAGER role")
                void manager_cannotInvite_manager() {
                        when(organizationService.findById(1)).thenReturn(org);
                        mockActorAs(manager, OrganizationRoleEnum.MANAGER);

                        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                                        () -> sut.createInvitation(1,
                                                        new CreateInvitationDto().setEmail("x@test.com")
                                                                        .setRole(OrganizationRoleEnum.MANAGER),
                                                        manager));
                        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
                }

                @Test
                @DisplayName("MANAGER should NOT be able to invite with OWNER role")
                void manager_cannotInvite_owner() {
                        when(organizationService.findById(1)).thenReturn(org);
                        mockActorAs(manager, OrganizationRoleEnum.MANAGER);

                        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                                        () -> sut.createInvitation(1,
                                                        new CreateInvitationDto().setEmail("x@test.com")
                                                                        .setRole(OrganizationRoleEnum.OWNER),
                                                        manager));
                        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
                }

                @Test
                @DisplayName("USER should NOT be able to invite anyone (defense in depth)")
                void user_cannotInvite() {
                        when(organizationService.findById(1)).thenReturn(org);
                        mockActorAs(regularUser, OrganizationRoleEnum.USER);

                        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                                        () -> sut.createInvitation(1,
                                                        new CreateInvitationDto().setEmail("x@test.com")
                                                                        .setRole(OrganizationRoleEnum.USER),
                                                        regularUser));
                        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
                }

                @Test
                @DisplayName("should throw 400 when email is missing")
                void should_throw400_when_emailMissing() {
                        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                                        () -> sut.createInvitation(1,
                                                        new CreateInvitationDto().setRole(OrganizationRoleEnum.USER),
                                                        owner));
                        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
                }

                @Test
                @DisplayName("should throw 400 when role is missing")
                void should_throw400_when_roleMissing() {
                        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                                        () -> sut.createInvitation(1,
                                                        new CreateInvitationDto().setEmail("x@test.com"), owner));
                        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
                }

                @Test
                @DisplayName("should throw 409 when invitee is already a member")
                void should_throw409_when_alreadyMember() {
                        when(organizationService.findById(1)).thenReturn(org);
                        mockActorAs(owner, OrganizationRoleEnum.OWNER);
                        OrganizationMember existing = new OrganizationMember().setUser(invitee)
                                        .setRole(OrganizationRoleEnum.USER);
                        when(memberRepository.findByOrganization(org)).thenReturn(List.of(existing));

                        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                                        () -> sut.createInvitation(1,
                                                        new CreateInvitationDto().setEmail(invitee.getEmail())
                                                                        .setRole(OrganizationRoleEnum.USER),
                                                        owner));
                        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
                }

                @Test
                @DisplayName("should throw 409 when there is already a pending invitation for the same email")
                void should_throw409_when_pendingInvitationExists() {
                        when(organizationService.findById(1)).thenReturn(org);
                        mockActorAs(owner, OrganizationRoleEnum.OWNER);
                        when(memberRepository.findByOrganization(org)).thenReturn(List.of());
                        when(invitationRepository.findByOrganizationAndEmailAndStatus(eq(org), eq("invitee@test.com"),
                                        eq(InvitationStatus.PENDING)))
                                        .thenReturn(Optional.of(new OrganizationInvitation()));

                        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                                        () -> sut.createInvitation(1,
                                                        new CreateInvitationDto().setEmail("invitee@test.com")
                                                                        .setRole(OrganizationRoleEnum.USER),
                                                        owner));
                        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
                }

                @Test
                @DisplayName("should throw 429 when pending invitations limit reached")
                void should_throw429_when_rateLimitReached() {
                        sut = new OrganizationInvitationService(
                                        invitationRepository, memberRepository, organizationService, auditService,
                                        emailService, userRepository, 2L, "http://localhost:3002");
                        when(organizationService.findById(1)).thenReturn(org);
                        mockActorAs(owner, OrganizationRoleEnum.OWNER);
                        when(invitationRepository.countByOrganizationAndStatus(org, InvitationStatus.PENDING))
                                        .thenReturn(2L);

                        ApiException ex = assertThrows(ApiException.class,
                                        () -> sut.createInvitation(1,
                                                        new CreateInvitationDto().setEmail("x@test.com")
                                                                        .setRole(OrganizationRoleEnum.USER),
                                                        owner));
                        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatus());
                        assertEquals(ErrorCodes.ORGANIZATIONS_INVITATION_LIMIT_REACHED, ex.getCode());
                        assertEquals(2L, ex.getParams().get("max"));
                }

                @Test
                @DisplayName("should not abort when email send fails (only logs warn)")
                void should_notAbort_when_emailFails() {
                        when(organizationService.findById(1)).thenReturn(org);
                        mockActorAs(owner, OrganizationRoleEnum.OWNER);
                        when(memberRepository.findByOrganization(org)).thenReturn(List.of());
                        when(invitationRepository.findByOrganizationAndEmailAndStatus(any(), any(), any()))
                                        .thenReturn(Optional.empty());
                        when(invitationRepository.save(any(OrganizationInvitation.class)))
                                        .thenAnswer(i -> i.getArgument(0));
                        doThrow(new RuntimeException("smtp down"))
                                        .when(emailService).sendInvitationEmail(anyString(), anyString(), anyString(),
                                                        anyString(), any(Language.class));

                        OrganizationInvitation inv = sut.createInvitation(1,
                                        new CreateInvitationDto().setEmail("x@test.com")
                                                        .setRole(OrganizationRoleEnum.USER),
                                        owner);

                        assertNotNull(inv);
                        verify(invitationRepository).save(any(OrganizationInvitation.class));
                }

                @Test
                @DisplayName("should send invitation in invitee's language when invitee is a registered user (EN)")
                void should_sendInvitation_inInviteeLanguage_when_inviteeRegistered() {
                        when(organizationService.findById(1)).thenReturn(org);
                        mockActorAs(owner, OrganizationRoleEnum.OWNER);
                        when(memberRepository.findByOrganization(org)).thenReturn(List.of());
                        when(invitationRepository.findByOrganizationAndEmailAndStatus(any(), any(), any()))
                                        .thenReturn(Optional.empty());
                        when(invitationRepository.save(any(OrganizationInvitation.class)))
                                        .thenAnswer(i -> i.getArgument(0));

                        User registeredInvitee = new User().setId(99).setEmail("invitee@test.com")
                                        .setLanguage(Language.EN);
                        when(userRepository.findByEmail("invitee@test.com")).thenReturn(Optional.of(registeredInvitee));
                        ArgumentCaptor<Language> languageCaptor = ArgumentCaptor.forClass(Language.class);

                        sut.createInvitation(1,
                                        new CreateInvitationDto().setEmail("invitee@test.com")
                                                        .setRole(OrganizationRoleEnum.USER),
                                        owner);

                        verify(emailService).sendInvitationEmail(anyString(), anyString(), anyString(), anyString(),
                                        languageCaptor.capture());
                        assertEquals(Language.EN, languageCaptor.getValue());
                }

                @Test
                @DisplayName("should default invitation language to ES when invitee email does not exist as a registered user")
                void should_defaultLanguageEs_when_inviteeNotRegistered() {
                        when(organizationService.findById(1)).thenReturn(org);
                        mockActorAs(owner, OrganizationRoleEnum.OWNER);
                        when(memberRepository.findByOrganization(org)).thenReturn(List.of());
                        when(invitationRepository.findByOrganizationAndEmailAndStatus(any(), any(), any()))
                                        .thenReturn(Optional.empty());
                        when(invitationRepository.save(any(OrganizationInvitation.class)))
                                        .thenAnswer(i -> i.getArgument(0));

                        when(userRepository.findByEmail("ghost@test.com")).thenReturn(Optional.empty());
                        ArgumentCaptor<Language> languageCaptor = ArgumentCaptor.forClass(Language.class);

                        sut.createInvitation(1,
                                        new CreateInvitationDto().setEmail("ghost@test.com")
                                                        .setRole(OrganizationRoleEnum.USER),
                                        owner);

                        verify(emailService).sendInvitationEmail(anyString(), anyString(), anyString(), anyString(),
                                        languageCaptor.capture());
                        assertEquals(Language.ES, languageCaptor.getValue());
                }

                @Test
                @DisplayName("should send invitation in invitee's language CA when invitee is registered with CA")
                void should_sendInvitation_inCaLanguage_when_inviteeRegisteredCa() {
                        when(organizationService.findById(1)).thenReturn(org);
                        mockActorAs(owner, OrganizationRoleEnum.OWNER);
                        when(memberRepository.findByOrganization(org)).thenReturn(List.of());
                        when(invitationRepository.findByOrganizationAndEmailAndStatus(any(), any(), any()))
                                        .thenReturn(Optional.empty());
                        when(invitationRepository.save(any(OrganizationInvitation.class)))
                                        .thenAnswer(i -> i.getArgument(0));

                        User registeredInvitee = new User().setId(99).setEmail("invitee@test.com")
                                        .setLanguage(Language.CA);
                        when(userRepository.findByEmail("invitee@test.com")).thenReturn(Optional.of(registeredInvitee));
                        ArgumentCaptor<Language> languageCaptor = ArgumentCaptor.forClass(Language.class);

                        sut.createInvitation(1,
                                        new CreateInvitationDto().setEmail("invitee@test.com")
                                                        .setRole(OrganizationRoleEnum.USER),
                                        owner);

                        verify(emailService).sendInvitationEmail(anyString(), anyString(), anyString(), anyString(),
                                        languageCaptor.capture());
                        assertEquals(Language.CA, languageCaptor.getValue());
                }
        }

        @Nested
        @DisplayName("listPendingInvitations")
        class ListPendingInvitations {

                @Test
                @DisplayName("should return only PENDING invitations of the org")
                void should_returnOnlyPending() {
                        when(organizationService.findById(1)).thenReturn(org);
                        List<OrganizationInvitation> list = List.of(new OrganizationInvitation());
                        when(invitationRepository.findByOrganizationAndStatus(org, InvitationStatus.PENDING))
                                        .thenReturn(list);

                        assertSame(list, sut.listPendingInvitations(1));
                }
        }

        @Nested
        @DisplayName("cancelInvitation")
        class CancelInvitation {

                @Test
                @DisplayName("should cancel pending invitation when actor is OWNER")
                void owner_canCancel() {
                        OrganizationInvitation inv = new OrganizationInvitation()
                                        .setId(50).setOrganization(org).setEmail("x@test.com")
                                        .setStatus(InvitationStatus.PENDING);
                        when(invitationRepository.findById(50)).thenReturn(Optional.of(inv));
                        mockActorAs(owner, OrganizationRoleEnum.OWNER);

                        sut.cancelInvitation(50, owner);

                        assertEquals(InvitationStatus.CANCELLED, inv.getStatus());
                        verify(invitationRepository).save(inv);
                        verify(auditService).log(eq(org), eq(owner), eq(AuditAction.INVITATION_CANCELLED), eq(null),
                                        any());
                }

                @Test
                @DisplayName("MANAGER can also cancel")
                void manager_canCancel() {
                        OrganizationInvitation inv = new OrganizationInvitation()
                                        .setId(50).setOrganization(org).setEmail("x@test.com")
                                        .setStatus(InvitationStatus.PENDING);
                        when(invitationRepository.findById(50)).thenReturn(Optional.of(inv));
                        mockActorAs(manager, OrganizationRoleEnum.MANAGER);

                        sut.cancelInvitation(50, manager);

                        assertEquals(InvitationStatus.CANCELLED, inv.getStatus());
                }

                @Test
                @DisplayName("USER cannot cancel")
                void user_cannotCancel() {
                        OrganizationInvitation inv = new OrganizationInvitation()
                                        .setId(50).setOrganization(org).setEmail("x@test.com")
                                        .setStatus(InvitationStatus.PENDING);
                        when(invitationRepository.findById(50)).thenReturn(Optional.of(inv));
                        mockActorAs(regularUser, OrganizationRoleEnum.USER);

                        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                                        () -> sut.cancelInvitation(50, regularUser));
                        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
                }

                @Test
                @DisplayName("should throw 404 when invitation does not exist")
                void should_throw404_when_notFound() {
                        when(invitationRepository.findById(50)).thenReturn(Optional.empty());

                        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                                        () -> sut.cancelInvitation(50, owner));
                        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
                }

                @Test
                @DisplayName("should throw 400 when invitation is not pending")
                void should_throw400_when_notPending() {
                        OrganizationInvitation inv = new OrganizationInvitation()
                                        .setId(50).setOrganization(org).setStatus(InvitationStatus.ACCEPTED);
                        when(invitationRepository.findById(50)).thenReturn(Optional.of(inv));
                        mockActorAs(owner, OrganizationRoleEnum.OWNER);

                        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                                        () -> sut.cancelInvitation(50, owner));
                        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
                }
        }

        @Nested
        @DisplayName("listMyInvitations")
        class ListMyInvitations {

                @Test
                @DisplayName("should return PENDING invitations for the actor's email")
                void should_returnByEmail() {
                        List<OrganizationInvitation> list = List.of(new OrganizationInvitation());
                        when(invitationRepository.findByEmailAndStatus(invitee.getEmail(), InvitationStatus.PENDING))
                                        .thenReturn(list);

                        assertSame(list, sut.listMyInvitations(invitee));
                }
        }

        @Nested
        @DisplayName("listAllMyInvitations")
        class ListAllMyInvitations {

                @Test
                @DisplayName("should return all invitations for the actor's email with PENDING first")
                void should_returnAllOrderedPendingFirst() {
                        OrganizationInvitation rejected = new OrganizationInvitation()
                                        .setStatus(InvitationStatus.REJECTED);
                        OrganizationInvitation pending = new OrganizationInvitation()
                                        .setStatus(InvitationStatus.PENDING);
                        OrganizationInvitation accepted = new OrganizationInvitation()
                                        .setStatus(InvitationStatus.ACCEPTED);
                        // repository returns in createdAt desc order
                        when(invitationRepository.findByEmailOrderByCreatedAtDesc(invitee.getEmail()))
                                        .thenReturn(List.of(rejected, pending, accepted));

                        List<OrganizationInvitation> result = sut.listAllMyInvitations(invitee);

                        assertEquals(3, result.size());
                        assertSame(pending, result.get(0));
                        assertSame(rejected, result.get(1));
                        assertSame(accepted, result.get(2));
                }

                @Test
                @DisplayName("should return empty list when actor has no invitations")
                void should_returnEmpty_when_noInvitations() {
                        when(invitationRepository.findByEmailOrderByCreatedAtDesc(invitee.getEmail()))
                                        .thenReturn(List.of());

                        assertEquals(0, sut.listAllMyInvitations(invitee).size());
                }
        }

        @Nested
        @DisplayName("acceptInvitation")
        class AcceptInvitation {

                @Test
                @DisplayName("should throw 404 when token does not exist")
                void should_throw404_when_tokenNotFound() {
                        when(invitationRepository.findByToken("tok")).thenReturn(Optional.empty());

                        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                                        () -> sut.acceptInvitation("tok", invitee));
                        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
                }

                @Test
                @DisplayName("should throw 403 when email does not match the actor")
                void should_throw403_when_emailMismatch() {
                        OrganizationInvitation inv = new OrganizationInvitation()
                                        .setEmail("other@test.com").setStatus(InvitationStatus.PENDING)
                                        .setExpiresAt(LocalDateTime.now().plusDays(1));
                        when(invitationRepository.findByToken("tok")).thenReturn(Optional.of(inv));

                        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                                        () -> sut.acceptInvitation("tok", invitee));
                        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
                }

                @Test
                @DisplayName("should throw 400 when invitation is not pending")
                void should_throw400_when_notPending() {
                        OrganizationInvitation inv = new OrganizationInvitation()
                                        .setEmail(invitee.getEmail()).setStatus(InvitationStatus.ACCEPTED)
                                        .setExpiresAt(LocalDateTime.now().plusDays(1));
                        when(invitationRepository.findByToken("tok")).thenReturn(Optional.of(inv));

                        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                                        () -> sut.acceptInvitation("tok", invitee));
                        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
                }

                @Test
                @DisplayName("should mark invitation EXPIRED and throw 410 when expired")
                void should_throw410_when_expired() {
                        OrganizationInvitation inv = new OrganizationInvitation()
                                        .setEmail(invitee.getEmail()).setStatus(InvitationStatus.PENDING)
                                        .setExpiresAt(LocalDateTime.now().minusDays(1));
                        when(invitationRepository.findByToken("tok")).thenReturn(Optional.of(inv));

                        ApiException ex = assertThrows(ApiException.class,
                                        () -> sut.acceptInvitation("tok", invitee));
                        assertEquals(HttpStatus.GONE, ex.getStatus());
                        assertEquals(ErrorCodes.ORGANIZATIONS_INVITATION_EXPIRED, ex.getCode());
                        assertEquals(InvitationStatus.EXPIRED, inv.getStatus());
                }

                @Test
                @DisplayName("should create membership and mark ACCEPTED on success")
                void should_createMembership_when_valid() {
                        OrganizationInvitation inv = new OrganizationInvitation()
                                        .setEmail(invitee.getEmail()).setStatus(InvitationStatus.PENDING)
                                        .setExpiresAt(LocalDateTime.now().plusDays(1))
                                        .setOrganization(org).setRole(OrganizationRoleEnum.MANAGER);
                        when(invitationRepository.findByToken("tok")).thenReturn(Optional.of(inv));
                        when(memberRepository.findByUserAndOrganization(invitee, org)).thenReturn(Optional.empty());
                        when(invitationRepository.save(inv)).thenReturn(inv);

                        sut.acceptInvitation("tok", invitee);

                        verify(memberRepository).save(any(OrganizationMember.class));
                        assertEquals(InvitationStatus.ACCEPTED, inv.getStatus());
                        assertNotNull(inv.getAcceptedAt());
                        verify(auditService).log(eq(org), eq(invitee), eq(AuditAction.INVITATION_ACCEPTED), eq(invitee),
                                        any());
                }

                @Test
                @DisplayName("should NOT create duplicate membership when already a member")
                void should_notCreateDuplicate_when_alreadyMember() {
                        OrganizationInvitation inv = new OrganizationInvitation()
                                        .setEmail(invitee.getEmail()).setStatus(InvitationStatus.PENDING)
                                        .setExpiresAt(LocalDateTime.now().plusDays(1))
                                        .setOrganization(org).setRole(OrganizationRoleEnum.USER);
                        when(invitationRepository.findByToken("tok")).thenReturn(Optional.of(inv));
                        when(memberRepository.findByUserAndOrganization(invitee, org))
                                        .thenReturn(Optional.of(new OrganizationMember()));
                        when(invitationRepository.save(inv)).thenReturn(inv);

                        sut.acceptInvitation("tok", invitee);

                        verify(memberRepository, never()).save(any(OrganizationMember.class));
                        assertEquals(InvitationStatus.ACCEPTED, inv.getStatus());
                }
        }

        @Nested
        @DisplayName("rejectInvitation")
        class RejectInvitation {

                @Test
                @DisplayName("should mark REJECTED on success")
                void should_reject() {
                        OrganizationInvitation inv = new OrganizationInvitation()
                                        .setEmail(invitee.getEmail()).setStatus(InvitationStatus.PENDING)
                                        .setOrganization(org);
                        when(invitationRepository.findByToken("tok")).thenReturn(Optional.of(inv));
                        when(invitationRepository.save(inv)).thenReturn(inv);

                        sut.rejectInvitation("tok", invitee);

                        assertEquals(InvitationStatus.REJECTED, inv.getStatus());
                        verify(auditService).log(eq(org), eq(invitee), eq(AuditAction.INVITATION_REJECTED), eq(invitee),
                                        any());
                }

                @Test
                @DisplayName("should throw 404 when token not found")
                void should_throw404() {
                        when(invitationRepository.findByToken("tok")).thenReturn(Optional.empty());

                        assertThrows(ResponseStatusException.class, () -> sut.rejectInvitation("tok", invitee));
                }

                @Test
                @DisplayName("should throw 403 when email mismatch")
                void should_throw403_when_emailMismatch() {
                        OrganizationInvitation inv = new OrganizationInvitation()
                                        .setEmail("other@test.com").setStatus(InvitationStatus.PENDING);
                        when(invitationRepository.findByToken("tok")).thenReturn(Optional.of(inv));

                        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                                        () -> sut.rejectInvitation("tok", invitee));
                        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
                }

                @Test
                @DisplayName("should throw 400 when not pending")
                void should_throw400_when_notPending() {
                        OrganizationInvitation inv = new OrganizationInvitation()
                                        .setEmail(invitee.getEmail()).setStatus(InvitationStatus.CANCELLED);
                        when(invitationRepository.findByToken("tok")).thenReturn(Optional.of(inv));

                        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                                        () -> sut.rejectInvitation("tok", invitee));
                        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
                }
        }
}
