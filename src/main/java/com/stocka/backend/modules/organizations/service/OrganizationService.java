package com.stocka.backend.modules.organizations.service;

import java.time.LocalDateTime;
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

import com.stocka.backend.modules.organizations.dto.CreateOrganizationDto;
import com.stocka.backend.modules.organizations.dto.UpdateOrganizationDto;
import com.stocka.backend.modules.organizations.entity.AuditAction;
import com.stocka.backend.modules.organizations.entity.InvitationStatus;
import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.organizations.entity.OrganizationInvitation;
import com.stocka.backend.modules.organizations.entity.OrganizationMember;
import com.stocka.backend.modules.organizations.entity.OrganizationRoleEnum;
import com.stocka.backend.modules.organizations.repository.OrganizationInvitationRepository;
import com.stocka.backend.modules.organizations.repository.OrganizationMemberRepository;
import com.stocka.backend.modules.organizations.repository.OrganizationRepository;
import com.stocka.backend.modules.users.entity.User;

@Service
public class OrganizationService {
    private static final Pattern SLUG_PATTERN = Pattern.compile("^[a-z0-9-]{3,40}$");
    private static final Set<String> RESERVED_SLUGS = Set.of(
            "admin", "api", "www", "app", "auth", "health", "users", "organizations", "invitations"
    );

    private final OrganizationRepository organizationRepository;
    private final OrganizationMemberRepository memberRepository;
    private final OrganizationInvitationRepository invitationRepository;
    private final OrganizationAuditService auditService;

    public OrganizationService(
            OrganizationRepository organizationRepository,
            OrganizationMemberRepository memberRepository,
            OrganizationInvitationRepository invitationRepository,
            OrganizationAuditService auditService
    ) {
        this.organizationRepository = organizationRepository;
        this.memberRepository = memberRepository;
        this.invitationRepository = invitationRepository;
        this.auditService = auditService;
    }

    @Transactional
    public Organization create(CreateOrganizationDto dto, User actor) {
        if (dto.getName() == null || dto.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El nombre es obligatorio");
        }
        validateSlug(dto.getSlug(), null);

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

        if (dto.getSlug() != null && !dto.getSlug().equals(org.getSlug())) {
            validateSlug(dto.getSlug(), org.getId());
            oldValues.put("slug", org.getSlug());
            newValues.put("slug", dto.getSlug());
            org.setSlug(dto.getSlug());
            changed = true;
        }

        if (!changed) {
            return org;
        }

        Organization saved = organizationRepository.save(org);
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

        List<OrganizationMember> members = memberRepository.findByOrganization(org);
        for (OrganizationMember m : members) {
            m.setDeletedAt(now);
            memberRepository.save(m);
        }

        List<OrganizationInvitation> pending =
                invitationRepository.findByOrganizationAndStatus(org, InvitationStatus.PENDING);
        for (OrganizationInvitation inv : pending) {
            inv.setStatus(InvitationStatus.CANCELLED);
            invitationRepository.save(inv);
        }

        org.setDeletedAt(now);
        organizationRepository.save(org);

        auditService.log(org, actor, AuditAction.ORG_DELETED, null, null);
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
    }
}
