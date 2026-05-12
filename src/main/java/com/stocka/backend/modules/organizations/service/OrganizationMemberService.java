package com.stocka.backend.modules.organizations.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.stocka.backend.modules.organizations.entity.AuditAction;
import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.organizations.entity.OrganizationMember;
import com.stocka.backend.modules.organizations.entity.OrganizationRoleEnum;
import com.stocka.backend.modules.organizations.repository.OrganizationMemberRepository;
import com.stocka.backend.modules.organizations.security.OrganizationSecurity;
import com.stocka.backend.modules.users.entity.User;

@Service
public class OrganizationMemberService {
    private final OrganizationMemberRepository memberRepository;
    private final OrganizationService organizationService;
    private final OrganizationAuditService auditService;

    public OrganizationMemberService(
            OrganizationMemberRepository memberRepository,
            OrganizationService organizationService,
            OrganizationAuditService auditService
    ) {
        this.memberRepository = memberRepository;
        this.organizationService = organizationService;
        this.auditService = auditService;
    }

    public List<OrganizationMember> listMembers(Integer orgId) {
        Organization org = organizationService.findById(orgId);
        return memberRepository.findByOrganization(org);
    }

    @Transactional
    public OrganizationMember updateMemberRole(
            Integer orgId,
            Integer memberId,
            OrganizationRoleEnum newRole,
            User actor
    ) {
        if (newRole == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El rol es obligatorio");
        }
        Organization org = organizationService.findById(orgId);
        OrganizationMember member = findMemberInOrg(memberId, org);
        ensureNotSelf(member, actor);

        OrganizationRoleEnum oldRole = member.getRole();
        if (oldRole == newRole) {
            return member;
        }

        if (oldRole == OrganizationRoleEnum.OWNER && newRole != OrganizationRoleEnum.OWNER) {
            ensureNotLastOwner(org);
        }

        member.setRole(newRole);
        OrganizationMember saved = memberRepository.save(member);

        auditService.log(org, actor, AuditAction.MEMBER_ROLE_CHANGED, member.getUser(), Map.of(
                "oldRole", oldRole.name(),
                "newRole", newRole.name()
        ));
        return saved;
    }

    @Transactional
    public void removeMember(Integer orgId, Integer memberId, User actor) {
        Organization org = organizationService.findById(orgId);
        OrganizationMember member = findMemberInOrg(memberId, org);
        ensureNotSelf(member, actor);
        OrganizationRoleEnum actorRole = resolveActorRole(org, actor);

        if (!canActOnMember(actorRole, member.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "No tienes permiso para quitar a este miembro");
        }

        if (member.getRole() == OrganizationRoleEnum.OWNER) {
            ensureNotLastOwner(org);
        }

        member.setDeletedAt(LocalDateTime.now());
        memberRepository.save(member);

        auditService.log(org, actor, AuditAction.MEMBER_REMOVED, member.getUser(), Map.of(
                "removedRole", member.getRole().name()
        ));
    }

    @Transactional
    public void leaveOrganization(Integer orgId, User actor) {
        Organization org = organizationService.findById(orgId);
        OrganizationMember member = memberRepository.findByUserAndOrganization(actor, org)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No eres miembro de esta organización"));

        if (member.getRole() == OrganizationRoleEnum.OWNER) {
            ensureNotLastOwner(org);
        }

        member.setDeletedAt(LocalDateTime.now());
        memberRepository.save(member);

        auditService.log(org, actor, AuditAction.MEMBER_LEFT, actor, Map.of(
                "leftRole", member.getRole().name()
        ));
    }

    private OrganizationMember findMemberInOrg(Integer memberId, Organization org) {
        OrganizationMember member = memberRepository.findById(memberId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Miembro no encontrado"));
        if (!member.getOrganization().getId().equals(org.getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Miembro no encontrado");
        }
        return member;
    }

    private OrganizationRoleEnum resolveActorRole(Organization org, User actor) {
        if (OrganizationSecurity.isGlobalAdmin(actor)) {
            return OrganizationRoleEnum.OWNER;
        }
        return memberRepository.findByUserAndOrganization(actor, org)
                .map(OrganizationMember::getRole)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "No eres miembro de esta organización"));
    }

    private boolean canActOnMember(OrganizationRoleEnum actorRole, OrganizationRoleEnum targetRole) {
        if (actorRole == OrganizationRoleEnum.OWNER) {
            return true;
        }
        if (actorRole == OrganizationRoleEnum.MANAGER) {
            return targetRole == OrganizationRoleEnum.USER || targetRole == OrganizationRoleEnum.SPECTATOR;
        }
        return false;
    }

    private void ensureNotSelf(OrganizationMember member, User actor) {
        if (member.getUser().getId().equals(actor.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "no puedes operar sobre tu propia membresía");
        }
    }

    private void ensureNotLastOwner(Organization org) {
        long owners = memberRepository.countByOrganizationAndRole(org, OrganizationRoleEnum.OWNER);
        if (owners <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "La organización debe tener al menos un OWNER");
        }
    }
}
