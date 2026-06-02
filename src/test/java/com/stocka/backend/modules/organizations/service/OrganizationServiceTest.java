package com.stocka.backend.modules.organizations.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.stocka.backend.modules.common.dto.AvailabilityResponse;
import com.stocka.backend.modules.common.dto.AvailabilityResponse.Reason;
import com.stocka.backend.modules.common.error.ApiException;
import com.stocka.backend.modules.common.error.ErrorCodes;
import com.stocka.backend.modules.organizations.dto.CreateOrganizationDto;
import com.stocka.backend.modules.organizations.dto.UpdateOrganizationDto;
import com.stocka.backend.modules.organizations.entity.AuditAction;
import com.stocka.backend.modules.organizations.entity.InvitationStatus;
import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.organizations.entity.OrganizationInvitation;
import com.stocka.backend.modules.organizations.entity.OrganizationMember;
import com.stocka.backend.modules.organizations.entity.OrganizationRoleEnum;
import com.stocka.backend.modules.organizations.entity.OrganizationSlugHistory;
import com.stocka.backend.modules.locations.repository.LocationRepository;
import com.stocka.backend.modules.notifications.preferences.repository.NotificationPreferenceRepository;
import com.stocka.backend.modules.organizations.repository.OrganizationInvitationRepository;
import com.stocka.backend.modules.organizations.repository.OrganizationMemberRepository;
import com.stocka.backend.modules.organizations.repository.OrganizationPieceAttributeRepository;
import com.stocka.backend.modules.organizations.repository.OrganizationRepository;
import com.stocka.backend.modules.organizations.repository.OrganizationSlugHistoryRepository;
import com.stocka.backend.modules.pieces.repository.PieceAttachmentRepository;
import com.stocka.backend.modules.pieces.repository.PieceAttributeValueRepository;
import com.stocka.backend.modules.pieces.repository.PieceOrganizationAttributeValueRepository;
import com.stocka.backend.modules.pieces.repository.PieceRepository;
import com.stocka.backend.modules.piecetypes.repository.PieceTypeActionRepository;
import com.stocka.backend.modules.piecetypes.repository.PieceTypeAttributeRepository;
import com.stocka.backend.modules.piecetypes.repository.PieceTypeRepository;
import com.stocka.backend.modules.users.entity.User;

@ExtendWith(MockitoExtension.class)
@DisplayName("OrganizationService")
class OrganizationServiceTest {

    @Mock private OrganizationRepository organizationRepository;
    @Mock private OrganizationMemberRepository memberRepository;
    @Mock private OrganizationInvitationRepository invitationRepository;
    @Mock private OrganizationSlugHistoryRepository slugHistoryRepository;
    @Mock private OrganizationAuditService auditService;
    @Spy private OrganizationQuotaProperties quotas = new OrganizationQuotaProperties();
    @Mock private PieceRepository pieceRepository;
    @Mock private PieceAttachmentRepository pieceAttachmentRepository;
    @Mock private PieceAttributeValueRepository pieceAttributeValueRepository;
    @Mock private PieceOrganizationAttributeValueRepository pieceOrganizationAttributeValueRepository;
    @Mock private LocationRepository locationRepository;
    @Mock private PieceTypeRepository pieceTypeRepository;
    @Mock private PieceTypeAttributeRepository pieceTypeAttributeRepository;
    @Mock private PieceTypeActionRepository pieceTypeActionRepository;
    @Mock private OrganizationPieceAttributeRepository organizationPieceAttributeRepository;
    @Mock private NotificationPreferenceRepository notificationPreferenceRepository;

    @InjectMocks private OrganizationService sut;

    private User actor;

    @BeforeEach
    void setUp() {
        actor = new User().setId(1).setEmail("a@test.com").setName("Actor");
    }

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("should save org, create OWNER membership and write audit")
        void should_createOrgWithOwnerMembershipAndAudit() {
            CreateOrganizationDto dto = new CreateOrganizationDto().setName("Acme").setSlug("acme");
            when(organizationRepository.findBySlug("acme")).thenReturn(Optional.empty());
            when(organizationRepository.save(any(Organization.class)))
                    .thenAnswer(i -> ((Organization) i.getArgument(0)).setId(42));

            Organization saved = sut.create(dto, actor);

            assertEquals(42, saved.getId());
            assertEquals("Acme", saved.getName());
            assertEquals("acme", saved.getSlug());

            ArgumentCaptor<OrganizationMember> mem = ArgumentCaptor.forClass(OrganizationMember.class);
            verify(memberRepository).save(mem.capture());
            assertSame(actor, mem.getValue().getUser());
            assertEquals(OrganizationRoleEnum.OWNER, mem.getValue().getRole());

            verify(auditService).log(eq(saved), eq(actor), eq(AuditAction.ORG_CREATED), eq(null), any());
        }

        @Test
        @DisplayName("should throw 400 when name is blank")
        void should_throw400_when_nameBlank() {
            CreateOrganizationDto dto = new CreateOrganizationDto().setName("  ").setSlug("acme");
            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> sut.create(dto, actor));
            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        }

        @Test
        @DisplayName("should throw 400 when slug has invalid format")
        void should_throw400_when_slugInvalid() {
            CreateOrganizationDto dto = new CreateOrganizationDto().setName("Acme").setSlug("Bad Slug!");
            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> sut.create(dto, actor));
            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        }

        @Test
        @DisplayName("should throw 400 when slug is reserved")
        void should_throw400_when_slugReserved() {
            CreateOrganizationDto dto = new CreateOrganizationDto().setName("Acme").setSlug("admin");
            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> sut.create(dto, actor));
            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        }

        @Test
        @DisplayName("should throw 409 when slug already exists")
        void should_throw409_when_slugDuplicated() {
            CreateOrganizationDto dto = new CreateOrganizationDto().setName("Acme").setSlug("acme");
            when(organizationRepository.findBySlug("acme"))
                    .thenReturn(Optional.of(new Organization().setId(99)));
            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> sut.create(dto, actor));
            assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
            verify(organizationRepository, never()).save(any());
        }

        @Test
        @DisplayName("should throw 403 organizations.quota_exceeded when the user owns the max orgs")
        void should_throw403_when_orgsPerUserQuotaReached() {
            quotas.setMaxOrgsPerUser(2);
            CreateOrganizationDto dto = new CreateOrganizationDto().setName("Acme").setSlug("acme");
            when(organizationRepository.findBySlug("acme")).thenReturn(Optional.empty());
            when(memberRepository.countByUserAndRole(actor, OrganizationRoleEnum.OWNER))
                    .thenReturn(2L);

            ApiException ex = assertThrows(ApiException.class, () -> sut.create(dto, actor));

            assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
            assertEquals(ErrorCodes.ORGANIZATIONS_QUOTA_EXCEEDED, ex.getCode());
            assertEquals("max_orgs_per_user", ex.getParams().get("limit"));
            verify(organizationRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("checkSlugAvailability")
    class CheckSlugAvailability {

        @org.junit.jupiter.params.ParameterizedTest(name = "[{index}] invalid: \"{0}\"")
        @org.junit.jupiter.params.provider.NullAndEmptySource
        @org.junit.jupiter.params.provider.ValueSource(strings = {
                "ab",                                          // too short
                "abcdefghijklmnopqrstuvwxyz0123456789-extra-x", // too long (41)
                "Acme",                                        // uppercase
                "ac me",                                       // space
                "acme!",                                       // special char
                "acmé",                                        // unicode
                "acme_org"                                     // underscore not allowed
        })
        @DisplayName("should return INVALID_FORMAT for malformed slugs")
        void should_returnInvalidFormat_when_malformed(String input) {
            AvailabilityResponse res = sut.checkSlugAvailability(input);

            assertFalse(res.available());
            assertEquals(Reason.INVALID_FORMAT, res.reason());
        }

        @org.junit.jupiter.params.ParameterizedTest(name = "[{index}] reserved: {0}")
        @org.junit.jupiter.params.provider.ValueSource(strings = {
                "admin", "api", "www", "app", "auth", "health",
                "users", "organizations", "invitations"
        })
        @DisplayName("should return RESERVED for reserved slugs")
        void should_returnReserved_when_reserved(String input) {
            AvailabilityResponse res = sut.checkSlugAvailability(input);

            assertFalse(res.available());
            assertEquals(Reason.RESERVED, res.reason());
        }

        @Test
        @DisplayName("should return TAKEN when the slug already exists")
        void should_returnTaken_when_slugExists() {
            when(organizationRepository.existsBySlug("acme")).thenReturn(true);

            AvailabilityResponse res = sut.checkSlugAvailability("acme");

            assertFalse(res.available());
            assertEquals(Reason.TAKEN, res.reason());
        }

        @Test
        @DisplayName("should return available when format is valid, not reserved, and free")
        void should_returnAvailable_when_validAndFree() {
            when(organizationRepository.existsBySlug("acme")).thenReturn(false);

            AvailabilityResponse res = sut.checkSlugAvailability("acme");

            assertTrue(res.available());
            assertNull(res.reason());
        }

        @Test
        @DisplayName("should accept slugs at the boundary lengths (3 and 40)")
        void should_returnAvailable_when_lengthAtBoundaries() {
            String fortyChars = "abcdefghij-abcdefghij-abcdefghij-abcdefg";
            assertEquals(40, fortyChars.length());
            when(organizationRepository.existsBySlug("abc")).thenReturn(false);
            when(organizationRepository.existsBySlug(fortyChars)).thenReturn(false);

            assertTrue(sut.checkSlugAvailability("abc").available());
            assertTrue(sut.checkSlugAvailability(fortyChars).available());
        }
    }

    @Nested
    @DisplayName("findUserOrganizations")
    class FindUserOrganizations {

        @Test
        @DisplayName("should return organizations from user memberships")
        void should_returnOrgsFromMemberships() {
            Organization o1 = new Organization().setId(1).setSlug("a");
            Organization o2 = new Organization().setId(2).setSlug("b");
            when(memberRepository.findByUser(actor)).thenReturn(List.of(
                    new OrganizationMember().setOrganization(o1),
                    new OrganizationMember().setOrganization(o2)
            ));

            List<Organization> result = sut.findUserOrganizations(actor);

            assertEquals(2, result.size());
        }
    }

    @Nested
    @DisplayName("findById")
    class FindById {

        @Test
        @DisplayName("should return the organization when it exists")
        void should_returnOrg_when_exists() {
            Organization org = new Organization().setId(1);
            when(organizationRepository.findById(1)).thenReturn(Optional.of(org));
            assertSame(org, sut.findById(1));
        }

        @Test
        @DisplayName("should throw 404 when organization does not exist")
        void should_throw404_when_notFound() {
            when(organizationRepository.findById(1)).thenReturn(Optional.empty());
            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> sut.findById(1));
            assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        }
    }

    @Nested
    @DisplayName("update")
    class Update {

        @Test
        @DisplayName("should update name and write audit")
        void should_updateName() {
            Organization org = new Organization().setId(1).setName("Old").setSlug("old-slug");
            when(organizationRepository.findById(1)).thenReturn(Optional.of(org));
            when(organizationRepository.save(any(Organization.class))).thenAnswer(i -> i.getArgument(0));

            UpdateOrganizationDto dto = new UpdateOrganizationDto().setName("New");
            Organization result = sut.update(1, dto, actor);

            assertEquals("New", result.getName());
            verify(auditService).log(eq(result), eq(actor), eq(AuditAction.ORG_UPDATED), eq(null), any());
        }

        @Test
        @DisplayName("should update slug when valid and different")
        void should_updateSlug_when_valid() {
            Organization org = new Organization().setId(1).setName("X").setSlug("old-slug");
            when(organizationRepository.findById(1)).thenReturn(Optional.of(org));
            when(organizationRepository.findBySlug("new-slug")).thenReturn(Optional.empty());
            when(organizationRepository.save(any(Organization.class))).thenAnswer(i -> i.getArgument(0));

            Organization result = sut.update(1, new UpdateOrganizationDto().setSlug("new-slug"), actor);

            assertEquals("new-slug", result.getSlug());
        }

        @Test
        @DisplayName("should throw 400 when new slug is invalid")
        void should_throw400_when_slugInvalid() {
            Organization org = new Organization().setId(1).setName("X").setSlug("old-slug");
            when(organizationRepository.findById(1)).thenReturn(Optional.of(org));

            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> sut.update(1, new UpdateOrganizationDto().setSlug("BAD!"), actor));
            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        }

        @Test
        @DisplayName("should throw 400 when new slug is reserved")
        void should_throw400_when_slugReserved() {
            Organization org = new Organization().setId(1).setName("X").setSlug("old-slug");
            when(organizationRepository.findById(1)).thenReturn(Optional.of(org));

            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> sut.update(1, new UpdateOrganizationDto().setSlug("api"), actor));
            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        }

        @Test
        @DisplayName("should throw 409 when new slug already taken by another org")
        void should_throw409_when_slugTakenByOther() {
            Organization org = new Organization().setId(1).setName("X").setSlug("old-slug");
            when(organizationRepository.findById(1)).thenReturn(Optional.of(org));
            when(organizationRepository.findBySlug("new-slug"))
                    .thenReturn(Optional.of(new Organization().setId(99)));

            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> sut.update(1, new UpdateOrganizationDto().setSlug("new-slug"), actor));
            assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        }

        @Test
        @DisplayName("should be a no-op when nothing changes")
        void should_noop_when_nothingChanges() {
            Organization org = new Organization().setId(1).setName("X").setSlug("old-slug");
            when(organizationRepository.findById(1)).thenReturn(Optional.of(org));

            sut.update(1, new UpdateOrganizationDto(), actor);

            verify(organizationRepository, never()).save(any());
            verify(auditService, never()).log(any(), any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("softDelete")
    class SoftDelete {

        @Test
        @DisplayName("should soft-delete org, members, cancel pending invitations, clear slug history and release the slug")
        void should_softDeleteOrgMembersAndInvitations() {
            Organization org = new Organization().setId(1).setSlug("acme");
            when(organizationRepository.findById(1)).thenReturn(Optional.of(org));
            OrganizationMember m1 = new OrganizationMember().setId(10);
            OrganizationMember m2 = new OrganizationMember().setId(11);
            when(memberRepository.findByOrganization(org)).thenReturn(List.of(m1, m2));
            OrganizationInvitation inv = new OrganizationInvitation().setId(20).setStatus(InvitationStatus.PENDING);
            when(invitationRepository.findByOrganization(org)).thenReturn(List.of(inv));

            sut.softDelete(1, actor);

            assertNotNull(m1.getDeletedAt());
            assertNotNull(m2.getDeletedAt());
            verify(memberRepository, times(2)).save(any(OrganizationMember.class));
            // PENDING -> CANCELLED + deletedAt is the audit-friendly path; closed invitations
            // (ACCEPTED/REJECTED/EXPIRED/CANCELLED) only receive deletedAt because their status
            // already documents how they ended.
            assertEquals(InvitationStatus.CANCELLED, inv.getStatus());
            assertNotNull(inv.getDeletedAt());
            verify(invitationRepository, atLeastOnce()).save(inv);
            // Cascade soft-delete to every child whose FK to Organization is non-nullable.
            verify(pieceAttributeValueRepository).deleteByOrganization(org);
            verify(pieceOrganizationAttributeValueRepository).deleteByOrganization(org);
            verify(pieceAttachmentRepository).softDeleteByOrganization(org);
            verify(pieceTypeAttributeRepository).softDeleteByOrganization(org);
            verify(pieceTypeRepository).softDeleteByOrganization(org);
            verify(organizationPieceAttributeRepository).softDeleteByOrganization(org);
            verify(pieceRepository).softDeleteByOrganization(org);
            verify(locationRepository).softDeleteByOrganization(org);
            verify(notificationPreferenceRepository).softDeleteByOrganization(org);
            assertNotNull(org.getDeletedAt());
            verify(organizationRepository, atLeastOnce()).save(org);
            verify(slugHistoryRepository).deleteByOrganization(org);
            // Active slug is rewritten to a marker that fails SLUG_PATTERN so it cannot collide
            // with a real slug and other orgs can now claim "acme".
            assertTrue(org.getSlug().startsWith("__deleted_1_"));
            assertTrue(org.getSlug().endsWith("__"));
            assertFalse(org.getSlug().equals("acme"));

            ArgumentCaptor<Map<String, Object>> oldValues = ArgumentCaptor.forClass(Map.class);
            verify(auditService).log(eq(org), eq(actor), eq(AuditAction.ORG_DELETED), eq(null), oldValues.capture());
            assertEquals("acme", oldValues.getValue().get("slug"));
        }
    }
}
