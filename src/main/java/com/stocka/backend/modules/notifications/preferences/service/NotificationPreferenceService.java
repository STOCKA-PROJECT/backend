package com.stocka.backend.modules.notifications.preferences.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.stocka.backend.modules.notifications.preferences.dto.NotificationPreferenceResponseDto;
import com.stocka.backend.modules.notifications.preferences.dto.UpdateNotificationPreferenceDto;
import com.stocka.backend.modules.notifications.preferences.entity.LifecycleAction;
import com.stocka.backend.modules.notifications.preferences.entity.NotificationPreference;
import com.stocka.backend.modules.notifications.preferences.entity.PieceScope;
import com.stocka.backend.modules.notifications.preferences.repository.NotificationPreferenceRepository;
import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.organizations.entity.OrganizationMember;
import com.stocka.backend.modules.organizations.repository.OrganizationMemberRepository;
import com.stocka.backend.modules.users.entity.User;

/**
 * Read/write API for per-user, per-organization email-notification preferences. Missing
 * rows are interpreted as defaults at read time so users opt in explicitly without forcing
 * a backfill. The same defaults are reused by the dispatch listener when it has to decide
 * whether to notify a member that has never visited the settings screen.
 */
@Service
public class NotificationPreferenceService {

    private final NotificationPreferenceRepository repository;
    private final OrganizationMemberRepository memberRepository;

    public NotificationPreferenceService(
            NotificationPreferenceRepository repository,
            OrganizationMemberRepository memberRepository
    ) {
        this.repository = repository;
        this.memberRepository = memberRepository;
    }

    /**
     * Lists the caller's preference for every active organization they belong to. Missing
     * rows are returned with the default values rather than auto-persisted.
     */
    public List<NotificationPreferenceResponseDto> listForUser(User user) {
        List<OrganizationMember> memberships = memberRepository.findByUser(user);
        List<Organization> orgs = memberships.stream()
                .map(OrganizationMember::getOrganization)
                .filter(o -> o != null && o.getDeletedAt() == null)
                .toList();
        if (orgs.isEmpty()) {
            return List.of();
        }

        Map<Integer, NotificationPreference> persistedByOrgId = new HashMap<>();
        for (NotificationPreference pref : repository.findByUserAndOrganizationIn(user, orgs)) {
            persistedByOrgId.put(pref.getOrganization().getId(), pref);
        }

        List<NotificationPreferenceResponseDto> out = new ArrayList<>(orgs.size());
        for (Organization org : orgs) {
            NotificationPreference pref = persistedByOrgId.get(org.getId());
            if (pref == null) {
                pref = transientDefault(user, org);
            }
            out.add(NotificationPreferenceResponseDto.from(org, pref));
        }
        out.sort(Comparator.comparing(
                NotificationPreferenceResponseDto::organizationName,
                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)
        ));
        return out;
    }

    /**
     * Persists the caller's preference for {@code organizationId}, upserting on the
     * unique {@code (user_id, organization_id)} constraint. A first concurrent insert that
     * loses the race retries as an update so the returned row reflects the merged state.
     */
    @Transactional
    public NotificationPreferenceResponseDto upsert(User user, Integer organizationId, UpdateNotificationPreferenceDto dto) {
        validateDto(dto);
        Organization org = resolveActiveMembership(user, organizationId);

        Optional<NotificationPreference> existing = repository.findByUserAndOrganization(user, org);
        NotificationPreference pref = existing.orElseGet(() -> new NotificationPreference()
                .setUser(user)
                .setOrganization(org));
        pref.setPieces(safeCopy(dto.getPieces()));
        pref.setPieceScope(dto.getPieceScope());
        pref.setLocations(safeCopy(dto.getLocations()));
        pref.setPieceTypes(safeCopy(dto.getPieceTypes()));
        pref.setDeletedAt(null);

        NotificationPreference saved;
        try {
            saved = repository.save(pref);
        } catch (DataIntegrityViolationException ex) {
            // First insert lost the race against a concurrent caller; refetch and update.
            NotificationPreference winner = repository.findByUserAndOrganization(user, org)
                    .orElseThrow(() -> ex);
            winner.setPieces(safeCopy(dto.getPieces()));
            winner.setPieceScope(dto.getPieceScope());
            winner.setLocations(safeCopy(dto.getLocations()));
            winner.setPieceTypes(safeCopy(dto.getPieceTypes()));
            winner.setDeletedAt(null);
            saved = repository.save(winner);
        }
        return NotificationPreferenceResponseDto.from(org, saved);
    }

    /**
     * Returns the persisted preference for {@code (user, org)} or a transient default
     * when no row exists. Used by the dispatch listener at flush time so a user that has
     * never touched their settings still receives their default subset of notifications.
     */
    public NotificationPreference resolveOrDefault(User user, Organization organization) {
        return repository.findByUserAndOrganization(user, organization)
                .orElseGet(() -> transientDefault(user, organization));
    }

    @Transactional
    public void softDeleteFor(User user, Organization organization) {
        repository.softDeleteByUserAndOrganization(user, organization);
    }

    @Transactional
    public void softDeleteAllFor(User user) {
        repository.softDeleteByUser(user);
    }

    private NotificationPreference transientDefault(User user, Organization organization) {
        return new NotificationPreference()
                .setUser(user)
                .setOrganization(organization)
                .setPieces(EnumSet.of(LifecycleAction.CREATED))
                .setPieceScope(PieceScope.OWNED_ONLY)
                .setLocations(EnumSet.noneOf(LifecycleAction.class))
                .setPieceTypes(EnumSet.noneOf(LifecycleAction.class));
    }

    private Organization resolveActiveMembership(User user, Integer organizationId) {
        if (organizationId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "organizationId es obligatorio");
        }
        return memberRepository.findByUser(user).stream()
                .map(OrganizationMember::getOrganization)
                .filter(o -> o != null
                        && o.getDeletedAt() == null
                        && organizationId.equals(o.getId()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No eres miembro de esta organización"));
    }

    private void validateDto(UpdateNotificationPreferenceDto dto) {
        if (dto == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cuerpo de la solicitud vacío");
        }
        if (dto.getPieces() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "pieces es obligatorio (puede ser un array vacío)");
        }
        if (dto.getLocations() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "locations es obligatorio (puede ser un array vacío)");
        }
        if (dto.getPieceTypes() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "pieceTypes es obligatorio (puede ser un array vacío)");
        }
        if (dto.getPieceScope() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "pieceScope es obligatorio");
        }
    }

    private static Set<LifecycleAction> safeCopy(Set<LifecycleAction> source) {
        if (source == null || source.isEmpty()) {
            return EnumSet.noneOf(LifecycleAction.class);
        }
        return EnumSet.copyOf(source);
    }
}
