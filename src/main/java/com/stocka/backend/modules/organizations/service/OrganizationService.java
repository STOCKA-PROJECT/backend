package com.stocka.backend.modules.organizations.service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.stocka.backend.modules.common.dto.AvailabilityResponse;
import com.stocka.backend.modules.common.dto.AvailabilityResponse.Reason;
import com.stocka.backend.modules.common.error.ApiException;
import com.stocka.backend.modules.common.error.ErrorCodes;
import com.stocka.backend.modules.locations.repository.LocationRepository;
import com.stocka.backend.modules.notifications.preferences.repository.NotificationPreferenceRepository;
import com.stocka.backend.modules.organizations.dto.CreateOrganizationDto;
import com.stocka.backend.modules.organizations.dto.UpdateOrganizationDto;
import com.stocka.backend.modules.organizations.entity.AuditAction;
import com.stocka.backend.modules.organizations.entity.InvitationStatus;
import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.organizations.entity.OrganizationInvitation;
import com.stocka.backend.modules.organizations.entity.OrganizationMember;
import com.stocka.backend.modules.organizations.entity.OrganizationRoleEnum;
import com.stocka.backend.modules.organizations.entity.OrganizationSlugHistory;
import com.stocka.backend.modules.organizations.repository.OrganizationInvitationRepository;
import com.stocka.backend.modules.organizations.repository.OrganizationMemberRepository;
import com.stocka.backend.modules.organizations.repository.OrganizationPieceAttributeRepository;
import com.stocka.backend.modules.organizations.repository.OrganizationRepository;
import com.stocka.backend.modules.organizations.repository.OrganizationSlugHistoryRepository;
import com.stocka.backend.modules.pieces.repository.PieceAttachmentRepository;
import com.stocka.backend.modules.pieces.repository.PieceAttributeValueRepository;
import com.stocka.backend.modules.pieces.repository.PieceOrganizationAttributeValueRepository;
import com.stocka.backend.modules.pieces.repository.PieceRepository;
import com.stocka.backend.modules.piecetypes.repository.PieceTypeAttributeRepository;
import com.stocka.backend.modules.piecetypes.repository.PieceTypeRepository;
import com.stocka.backend.modules.users.entity.User;

@Service
public class OrganizationService {
    private static final Pattern SLUG_PATTERN = Pattern.compile("^[a-z0-9-]{3,40}$");
    private static final Set<String> RESERVED_SLUGS = Set.of(
            "admin", "api", "www", "app", "auth", "health", "users", "organizations", "invitations",
            "dashboard", "mi-cuenta", "crear-organizacion"
    );

    private final OrganizationRepository organizationRepository;
    private final OrganizationMemberRepository memberRepository;
    private final OrganizationInvitationRepository invitationRepository;
    private final OrganizationSlugHistoryRepository slugHistoryRepository;
    private final OrganizationAuditService auditService;
    private final OrganizationQuotaProperties quotas;
    private final PieceRepository pieceRepository;
    private final PieceAttachmentRepository pieceAttachmentRepository;
    private final PieceAttributeValueRepository pieceAttributeValueRepository;
    private final PieceOrganizationAttributeValueRepository pieceOrganizationAttributeValueRepository;
    private final LocationRepository locationRepository;
    private final PieceTypeRepository pieceTypeRepository;
    private final PieceTypeAttributeRepository pieceTypeAttributeRepository;
    private final OrganizationPieceAttributeRepository organizationPieceAttributeRepository;
    private final NotificationPreferenceRepository notificationPreferenceRepository;

    public OrganizationService(
            OrganizationRepository organizationRepository,
            OrganizationMemberRepository memberRepository,
            OrganizationInvitationRepository invitationRepository,
            OrganizationSlugHistoryRepository slugHistoryRepository,
            OrganizationAuditService auditService,
            OrganizationQuotaProperties quotas,
            PieceRepository pieceRepository,
            PieceAttachmentRepository pieceAttachmentRepository,
            PieceAttributeValueRepository pieceAttributeValueRepository,
            PieceOrganizationAttributeValueRepository pieceOrganizationAttributeValueRepository,
            LocationRepository locationRepository,
            PieceTypeRepository pieceTypeRepository,
            PieceTypeAttributeRepository pieceTypeAttributeRepository,
            OrganizationPieceAttributeRepository organizationPieceAttributeRepository,
            NotificationPreferenceRepository notificationPreferenceRepository
    ) {
        this.organizationRepository = organizationRepository;
        this.memberRepository = memberRepository;
        this.invitationRepository = invitationRepository;
        this.slugHistoryRepository = slugHistoryRepository;
        this.auditService = auditService;
        this.quotas = quotas;
        this.pieceRepository = pieceRepository;
        this.pieceAttachmentRepository = pieceAttachmentRepository;
        this.pieceAttributeValueRepository = pieceAttributeValueRepository;
        this.pieceOrganizationAttributeValueRepository = pieceOrganizationAttributeValueRepository;
        this.locationRepository = locationRepository;
        this.pieceTypeRepository = pieceTypeRepository;
        this.pieceTypeAttributeRepository = pieceTypeAttributeRepository;
        this.organizationPieceAttributeRepository = organizationPieceAttributeRepository;
        this.notificationPreferenceRepository = notificationPreferenceRepository;
    }

    @Transactional
    public Organization create(CreateOrganizationDto dto, User actor) {
        if (dto.getName() == null || dto.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El nombre es obligatorio");
        }
        validateSlug(dto.getSlug(), null);
        ensureUnderOrgsPerUserQuota(actor);

        Organization org = new Organization()
                .setName(dto.getName().trim())
                .setSlug(dto.getSlug());
        org = organizationRepository.save(org);

        OrganizationMember ownerMembership = new OrganizationMember()
                .setUser(actor)
                .setOrganization(org)
                .setRole(OrganizationRoleEnum.OWNER);
        memberRepository.save(ownerMembership);

        auditService.log(org, actor, AuditAction.ORG_CREATED, null, Map.of(
                "name", org.getName(),
                "slug", org.getSlug()
        ));

        return org;
    }

    public List<Organization> findUserOrganizations(User user) {
        return memberRepository.findByUser(user).stream()
                .map(OrganizationMember::getOrganization)
                .collect(Collectors.toList());
    }

    public Optional<OrganizationRoleEnum> getCurrentUserRole(Organization org, User user) {
        return memberRepository.findByUserAndOrganization(user, org)
                .map(OrganizationMember::getRole);
    }

    public Organization findById(Integer orgId) {
        return organizationRepository.findById(orgId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Organización no encontrada"));
    }

    @Transactional
    public Organization update(Integer orgId, UpdateOrganizationDto dto, User actor) {
        Organization org = findById(orgId);

        Map<String, Object> oldValues = new HashMap<>();
        Map<String, Object> newValues = new HashMap<>();
        boolean changed = false;

        if (dto.getName() != null && !dto.getName().equals(org.getName())) {
            if (dto.getName().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El nombre no puede estar vacío");
            }
            oldValues.put("name", org.getName());
            newValues.put("name", dto.getName().trim());
            org.setName(dto.getName().trim());
            changed = true;
        }

        String previousSlug = null;
        if (dto.getSlug() != null && !dto.getSlug().equals(org.getSlug())) {
            validateSlug(dto.getSlug(), org.getId());
            previousSlug = org.getSlug();
            oldValues.put("slug", previousSlug);
            newValues.put("slug", dto.getSlug());
            org.setSlug(dto.getSlug());
            changed = true;
        }

        if (!changed) {
            return org;
        }

        Organization saved = organizationRepository.save(org);
        if (previousSlug != null) {
            slugHistoryRepository.save(new OrganizationSlugHistory()
                    .setOrganization(saved)
                    .setOldSlug(previousSlug));
        }
        auditService.log(saved, actor, AuditAction.ORG_UPDATED, null, Map.of(
                "old", oldValues,
                "new", newValues
        ));
        return saved;
    }

    @Transactional
    public void softDelete(Integer orgId, User actor) {
        Organization org = findById(orgId);
        LocalDateTime now = LocalDateTime.now();

        // Cascade soft-delete to every child whose FK to Organization is non-nullable. Without
        // this, EAGER hydration of those children would later trip on the org's
        // @SQLRestriction("deleted_at IS NULL") (Hibernate -> ObjectNotFoundException). The bulk
        // updates below stamp deletedAt only on still-active rows so the call is idempotent.
        // Order: leaves first (so a partial failure does not leave parent rows referencing
        // missing children), but with bulk UPDATEs + soft-delete the order is non-critical.
        pieceAttributeValueRepository.deleteByOrganization(org);
        pieceOrganizationAttributeValueRepository.deleteByOrganization(org);
        pieceAttachmentRepository.softDeleteByOrganization(org);
        pieceTypeAttributeRepository.softDeleteByOrganization(org);
        pieceTypeRepository.softDeleteByOrganization(org);
        organizationPieceAttributeRepository.softDeleteByOrganization(org);
        pieceRepository.softDeleteByOrganization(org);
        locationRepository.softDeleteByOrganization(org);
        notificationPreferenceRepository.softDeleteByOrganization(org);

        List<OrganizationMember> members = memberRepository.findByOrganization(org);
        for (OrganizationMember m : members) {
            m.setDeletedAt(now);
            memberRepository.save(m);
        }

        // Soft-delete every invitation pointing to this org regardless of status. Pending ones
        // also flip their status to CANCELLED for audit clarity; closed invitations
        // (ACCEPTED/REJECTED/EXPIRED/CANCELLED) only receive deletedAt because their status
        // already documents how they ended. This prevents future hydration failures when
        // listing invitations whose org has been soft-deleted (Organization carries
        // @SQLRestriction("deleted_at IS NULL"), so EAGER joins would otherwise blow up).
        for (OrganizationInvitation inv : invitationRepository.findByOrganization(org)) {
            if (inv.getStatus() == InvitationStatus.PENDING) {
                inv.setStatus(InvitationStatus.CANCELLED);
            }
            inv.setDeletedAt(now);
            invitationRepository.save(inv);
        }

        // Release the slug so anyone can claim it after the org is gone: drop every history row
        // (frees previously used slugs) and rename the active slug to a marker that fails
        // SLUG_PATTERN so it cannot collide with a real slug. The row is preserved for audit;
        // only its slug column is mutated.
        String releasedSlug = org.getSlug();
        slugHistoryRepository.deleteByOrganization(org);
        org.setSlug("__deleted_" + org.getId() + "_" + now.toEpochSecond(ZoneOffset.UTC) + "__");
        org.setDeletedAt(now);
        organizationRepository.save(org);

        auditService.log(org, actor, AuditAction.ORG_DELETED, null, Map.of("slug", releasedSlug));
    }

    /**
     * Checks whether the given slug can be used for a new organization.
     *
     * @param slug candidate slug; may be {@code null}
     * @return an {@link AvailabilityResponse} describing the result; never {@code null}
     */
    public AvailabilityResponse checkSlugAvailability(String slug) {
        if (slug == null || !SLUG_PATTERN.matcher(slug).matches()) {
            return AvailabilityResponse.unavailable(Reason.INVALID_FORMAT);
        }
        if (RESERVED_SLUGS.contains(slug)) {
            return AvailabilityResponse.unavailable(Reason.RESERVED);
        }
        if (organizationRepository.existsBySlug(slug) || slugHistoryRepository.existsByOldSlug(slug)) {
            return AvailabilityResponse.unavailable(Reason.TAKEN);
        }
        return AvailabilityResponse.ok();
    }

    private void ensureUnderOrgsPerUserQuota(User actor) {
        long owned = memberRepository.countByUserAndRole(actor, OrganizationRoleEnum.OWNER);
        int max = quotas.getMaxOrgsPerUser();
        if (owned >= max) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    ErrorCodes.ORGANIZATIONS_QUOTA_EXCEEDED,
                    Map.of("limit", "max_orgs_per_user", "max", max, "current", owned));
        }
    }

    private void validateSlug(String slug, Integer currentOrgId) {
        if (slug == null || !SLUG_PATTERN.matcher(slug).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El slug debe tener entre 3 y 40 caracteres y solo contener minúsculas, números y guiones");
        }
        if (RESERVED_SLUGS.contains(slug)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El slug '" + slug + "' está reservado");
        }
        Optional<Organization> existing = organizationRepository.findBySlug(slug);
        if (existing.isPresent() && !existing.get().getId().equals(currentOrgId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ya existe una organización con ese slug");
        }
        // Reject slugs that another organization used in the past: keeping them claimable
        // would silently break old deep links by pointing them at a different org.
        slugHistoryRepository.findByOldSlug(slug).ifPresent(history -> {
            Integer ownerId = history.getOrganization().getId();
            if (!ownerId.equals(currentOrgId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Ese slug pertenece al historial de otra organización");
            }
        });
    }
}
