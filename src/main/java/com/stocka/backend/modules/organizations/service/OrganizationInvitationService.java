package com.stocka.backend.modules.organizations.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
import com.stocka.backend.modules.organizations.security.OrganizationSecurity;
import com.stocka.backend.modules.users.entity.Language;
import com.stocka.backend.modules.users.entity.User;
import com.stocka.backend.modules.users.repository.UserRepository;

@Service
public class OrganizationInvitationService {
    private static final Logger log = LoggerFactory.getLogger(OrganizationInvitationService.class);
    private static final long INVITATION_TTL_DAYS = 7;

    private final OrganizationInvitationRepository invitationRepository;
    private final OrganizationMemberRepository memberRepository;
    private final OrganizationService organizationService;
    private final OrganizationAuditService auditService;
    private final EmailService emailService;
    private final UserRepository userRepository;
    private final long maxPendingInvitations;
    private final String frontendBaseUrl;

    public OrganizationInvitationService(
            OrganizationInvitationRepository invitationRepository,
            OrganizationMemberRepository memberRepository,
            OrganizationService organizationService,
            OrganizationAuditService auditService,
            EmailService emailService,
            UserRepository userRepository,
            @Value("${app.organization.max-pending-invitations:50}") long maxPendingInvitations,
            @Value("${app.frontend.base-url:http://localhost:3002}") String frontendBaseUrl) {
        this.invitationRepository = invitationRepository;
        this.memberRepository = memberRepository;
        this.organizationService = organizationService;
        this.auditService = auditService;
        this.emailService = emailService;
        this.userRepository = userRepository;
        this.maxPendingInvitations = maxPendingInvitations;
        this.frontendBaseUrl = frontendBaseUrl;
    }

    @Transactional
    public OrganizationInvitation createInvitation(Integer orgId, CreateInvitationDto dto, User actor) {
        if (dto.getEmail() == null || dto.getEmail().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El email es obligatorio");
        }
        if (dto.getRole() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El rol es obligatorio");
        }

        Organization org = organizationService.findById(orgId);
        OrganizationRoleEnum actorRole = resolveActorRole(org, actor);
        validateActorCanInviteWithRole(actorRole, dto.getRole());

        if (invitationRepository.countByOrganizationAndStatus(org, InvitationStatus.PENDING) >= maxPendingInvitations) {
            throw new ApiException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    ErrorCodes.ORGANIZATIONS_INVITATION_LIMIT_REACHED,
                    Map.of("max", maxPendingInvitations));
        }

        String email = dto.getEmail().trim().toLowerCase();

        boolean alreadyMember = memberRepository.findByOrganization(org).stream()
                .anyMatch(m -> m.getUser().getEmail().equalsIgnoreCase(email));
        if (alreadyMember) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Este usuario ya es miembro de la organización");
        }

        if (invitationRepository.findByOrganizationAndEmailAndStatus(org, email, InvitationStatus.PENDING)
                .isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ya existe una invitación pendiente para este email");
        }

        OrganizationInvitation invitation = new OrganizationInvitation()
                .setOrganization(org)
                .setEmail(email)
                .setRole(dto.getRole())
                .setInvitedBy(actor)
                .setToken(UUID.randomUUID().toString())
                .setStatus(InvitationStatus.PENDING)
                .setExpiresAt(LocalDateTime.now().plusDays(INVITATION_TTL_DAYS));

        OrganizationInvitation saved = invitationRepository.save(invitation);

        try {
            String inviterName = (actor.getName() == null ? "" : actor.getName()).trim();
            if (inviterName.isEmpty()) {
                inviterName = actor.getEmail();
            }
            String acceptUrl = frontendBaseUrl + "/invitations/" + saved.getToken();
            Language inviteeLanguage = userRepository.findByEmail(email)
                    .map(User::getLanguage)
                    .orElse(Language.ES);
            emailService.sendInvitationEmail(email, inviterName, org.getName(), acceptUrl, inviteeLanguage);
        } catch (Exception e) {
            log.warn("Failed to send invitation email to {}: {}", email, e.getMessage());
        }

        auditService.log(org, actor, AuditAction.MEMBER_INVITED, null, Map.of(
                "email", email,
                "role", dto.getRole().name()));
        return saved;
    }

    public List<OrganizationInvitation> listPendingInvitations(Integer orgId) {
        Organization org = organizationService.findById(orgId);
        return invitationRepository.findByOrganizationAndStatus(org, InvitationStatus.PENDING);
    }

    @Transactional
    public void cancelInvitation(Integer orgId, Integer invitationId, User actor) {
        Organization org = organizationService.findById(orgId);
        // 404 (not "not in this org") so we don't leak invitation existence across orgs.
        OrganizationInvitation invitation = invitationRepository.findById(invitationId)
                .filter(inv -> inv.getOrganization().getId().equals(org.getId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invitación no encontrada"));

        OrganizationRoleEnum actorRole = resolveActorRole(org, actor);
        if (actorRole != OrganizationRoleEnum.OWNER && actorRole != OrganizationRoleEnum.MANAGER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "No tienes permiso para cancelar esta invitación");
        }

        if (invitation.getStatus() != InvitationStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La invitación ya no está pendiente");
        }

        invitation.setStatus(InvitationStatus.CANCELLED);
        invitationRepository.save(invitation);

        auditService.log(org, actor, AuditAction.INVITATION_CANCELLED, null, Map.of(
                "email", invitation.getEmail()));
    }

    public List<OrganizationInvitation> listMyInvitations(User actor) {
        return invitationRepository.findByEmailAndStatus(actor.getEmail(), InvitationStatus.PENDING);
    }

    /**
     * Returns every invitation addressed to the actor's email, regardless of
     * status. Pending invitations come first, followed by the rest ordered by
     * creation date (most recent first).
     *
     * @param actor the authenticated user whose invitations we want to list
     * @return ordered list of all invitations for the actor
     */
    public List<OrganizationInvitation> listAllMyInvitations(User actor) {
        List<OrganizationInvitation> all = invitationRepository.findByEmailOrderByCreatedAtDesc(actor.getEmail());
        return all.stream()
                .sorted((a, b) -> {
                    boolean aPending = a.getStatus() == InvitationStatus.PENDING;
                    boolean bPending = b.getStatus() == InvitationStatus.PENDING;
                    if (aPending != bPending) {
                        return aPending ? -1 : 1;
                    }
                    return 0; // preserve repository order (createdAt desc)
                })
                .toList();
    }

    @Transactional
    public OrganizationInvitation acceptInvitation(String token, User actor) {
        OrganizationInvitation invitation = invitationRepository.findByToken(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invitación no encontrada"));

        if (!invitation.getEmail().equalsIgnoreCase(actor.getEmail())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Esta invitación no está dirigida a tu email");
        }

        if (invitation.getStatus() != InvitationStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La invitación ya no está pendiente");
        }

        if (invitation.getExpiresAt().isBefore(LocalDateTime.now())) {
            invitation.setStatus(InvitationStatus.EXPIRED);
            invitationRepository.save(invitation);
            throw new ApiException(HttpStatus.GONE, ErrorCodes.ORGANIZATIONS_INVITATION_EXPIRED);
        }

        Organization org = invitation.getOrganization();
        boolean alreadyMember = memberRepository.findByUserAndOrganization(actor, org).isPresent();
        if (!alreadyMember) {
            OrganizationMember membership = new OrganizationMember()
                    .setUser(actor)
                    .setOrganization(org)
                    .setRole(invitation.getRole());
            memberRepository.save(membership);
        }

        invitation.setStatus(InvitationStatus.ACCEPTED);
        invitation.setAcceptedAt(LocalDateTime.now());
        OrganizationInvitation saved;
        try {
            // saveAndFlush forces the version check now so two concurrent accepts
            // resolve to one success and one 409 (instead of both inserting members).
            saved = invitationRepository.saveAndFlush(invitation);
        } catch (OptimisticLockingFailureException ex) {
            throw new ApiException(HttpStatus.CONFLICT,
                    ErrorCodes.ORGANIZATIONS_INVITATION_ALREADY_PROCESSED);
        }

        auditService.log(org, actor, AuditAction.INVITATION_ACCEPTED, actor, Map.of(
                "role", invitation.getRole().name()));
        return saved;
    }

    @Transactional
    public OrganizationInvitation rejectInvitation(String token, User actor) {
        OrganizationInvitation invitation = invitationRepository.findByToken(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invitación no encontrada"));

        if (!invitation.getEmail().equalsIgnoreCase(actor.getEmail())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Esta invitación no está dirigida a tu email");
        }

        if (invitation.getStatus() != InvitationStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La invitación ya no está pendiente");
        }

        invitation.setStatus(InvitationStatus.REJECTED);
        OrganizationInvitation saved = invitationRepository.save(invitation);

        auditService.log(invitation.getOrganization(), actor, AuditAction.INVITATION_REJECTED, actor, Map.of(
                "email", invitation.getEmail()));
        return saved;
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

    private void validateActorCanInviteWithRole(OrganizationRoleEnum actorRole, OrganizationRoleEnum invitedRole) {
        if (actorRole == OrganizationRoleEnum.OWNER) {
            return;
        }
        if (actorRole == OrganizationRoleEnum.MANAGER
                && (invitedRole == OrganizationRoleEnum.USER || invitedRole == OrganizationRoleEnum.SPECTATOR)) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "No tienes permiso para invitar con el rol " + invitedRole);
    }
}
