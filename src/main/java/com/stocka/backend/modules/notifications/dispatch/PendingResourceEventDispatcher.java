package com.stocka.backend.modules.notifications.dispatch;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.stocka.backend.modules.notifications.dispatch.entity.PendingResourceEvent;
import com.stocka.backend.modules.notifications.dispatch.repository.PendingResourceEventRepository;
import com.stocka.backend.modules.notifications.email.EmailService;
import com.stocka.backend.modules.notifications.events.ResourceKind;
import com.stocka.backend.modules.notifications.preferences.entity.LifecycleAction;
import com.stocka.backend.modules.notifications.preferences.entity.NotificationPreference;
import com.stocka.backend.modules.notifications.preferences.entity.PieceScope;
import com.stocka.backend.modules.notifications.preferences.service.NotificationPreferenceService;
import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.organizations.entity.OrganizationMember;
import com.stocka.backend.modules.organizations.repository.OrganizationMemberRepository;
import com.stocka.backend.modules.users.entity.Language;
import com.stocka.backend.modules.users.entity.User;
import com.stocka.backend.modules.users.repository.UserRepository;

/**
 * Per-row processor for the coalescing dispatch queue. Lives in its own bean so the
 * {@code @Transactional(REQUIRES_NEW)} advice goes through the proxy when invoked from the
 * scheduled flusher (Spring AOP does not advise self-invocations).
 */
@Service
public class PendingResourceEventDispatcher {

    private static final Logger log = LoggerFactory.getLogger(PendingResourceEventDispatcher.class);
    private static final Locale FALLBACK_LOCALE = Locale.of("es");

    private final PendingResourceEventRepository pendingRepository;
    private final OrganizationMemberRepository memberRepository;
    private final UserRepository userRepository;
    private final NotificationPreferenceService preferenceService;
    private final EmailService emailService;
    private final NotificationDispatchProperties properties;
    private final MessageSource messageSource;
    private final String frontendBaseUrl;

    public PendingResourceEventDispatcher(
            PendingResourceEventRepository pendingRepository,
            OrganizationMemberRepository memberRepository,
            UserRepository userRepository,
            NotificationPreferenceService preferenceService,
            EmailService emailService,
            NotificationDispatchProperties properties,
            MessageSource messageSource,
            @Value("${app.frontend.base-url:http://localhost:3002}") String frontendBaseUrl
    ) {
        this.pendingRepository = pendingRepository;
        this.memberRepository = memberRepository;
        this.userRepository = userRepository;
        this.preferenceService = preferenceService;
        this.emailService = emailService;
        this.properties = properties;
        this.messageSource = messageSource;
        this.frontendBaseUrl = stripTrailingSlash(frontendBaseUrl);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processOne(Integer id) {
        Optional<PendingResourceEvent> rowOpt = pendingRepository.findById(id);
        if (rowOpt.isEmpty()) {
            return;
        }
        PendingResourceEvent row = rowOpt.get();

        LifecycleAction effective = reduce(row.getFirstAction(), row.getLastAction());
        if (effective == null) {
            pendingRepository.delete(row);
            return;
        }

        Organization org = row.getOrganization();
        if (org == null || org.getDeletedAt() != null) {
            pendingRepository.delete(row);
            return;
        }

        String actorName = resolveActorName(row.getActorUserId());
        String resourceName = row.getResourceName() == null ? "" : row.getResourceName();
        String orgName = org.getName();
        String resourceUrl = buildResourceUrl(org.getId(), row.getResourceKind(), row.getResourceId(), effective);

        for (OrganizationMember member : memberRepository.findByOrganization(org)) {
            User recipient = member.getUser();
            if (recipient == null) continue;
            if (recipient.getId().equals(row.getActorUserId())) continue;
            if (recipient.getEmail() == null || !recipient.isEmailVerified()) continue;

            NotificationPreference pref = preferenceService.resolveOrDefault(recipient, org);
            if (pref == null) continue;

            Set<LifecycleAction> actions = switch (row.getResourceKind()) {
                case PIECE -> pref.getPieces();
                case LOCATION -> pref.getLocations();
                case PIECE_TYPE -> pref.getPieceTypes();
            };
            if (actions == null || !actions.contains(effective)) continue;

            if (row.getResourceKind() == ResourceKind.PIECE && pref.getPieceScope() == PieceScope.OWNED_ONLY) {
                if (row.getOwnerUserId() == null || !row.getOwnerUserId().equals(recipient.getId())) {
                    continue;
                }
            }

            try {
                emailService.sendResourceLifecycleEmail(
                        recipient.getEmail(),
                        row.getResourceKind(),
                        effective,
                        resourceName,
                        orgName,
                        actorName,
                        resourceUrl,
                        Optional.ofNullable(recipient.getLanguage()).orElse(Language.ES)
                );
            } catch (Exception ex) {
                // Per-recipient failure must not stop the rest of the fan-out.
                log.warn("Failed to send lifecycle email to user {} for event id {}: {}",
                        recipient.getId(), id, ex.getMessage());
            }
        }
        pendingRepository.delete(row);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void incrementOrDrop(Integer id) {
        pendingRepository.findById(id).ifPresent(row -> {
            int next = row.getAttempts() + 1;
            if (next >= properties.maxAttempts()) {
                log.error("Dropping pending resource event id={} after {} attempts", id, next);
                pendingRepository.delete(row);
            } else {
                row.setAttempts(next);
                pendingRepository.save(row);
            }
        });
    }

    /**
     * Reduces the (first, last) action pair captured during the quiet window into the
     * single action that should be communicated, or {@code null} when the resource was
     * created and deleted inside the same window (transient — no email).
     */
    static LifecycleAction reduce(LifecycleAction first, LifecycleAction last) {
        if (first == null || last == null) {
            return null;
        }
        if (first == LifecycleAction.CREATED && last == LifecycleAction.DELETED) {
            return null;
        }
        if (first == LifecycleAction.CREATED) {
            return LifecycleAction.CREATED;
        }
        if (first == LifecycleAction.EDITED && last == LifecycleAction.DELETED) {
            return LifecycleAction.DELETED;
        }
        if (first == LifecycleAction.EDITED) {
            return LifecycleAction.EDITED;
        }
        // first == DELETED: anything after a delete is defensively reported as DELETED.
        return LifecycleAction.DELETED;
    }

    private String resolveActorName(Integer actorUserId) {
        if (actorUserId == null) {
            return messageSource.getMessage("email.resourceLifecycle.actor.unknown",
                    null, "Alguien", FALLBACK_LOCALE);
        }
        return userRepository.findById(actorUserId)
                .map(PendingResourceEventDispatcher::displayName)
                .orElseGet(() -> messageSource.getMessage(
                        "email.resourceLifecycle.actor.unknown",
                        null, "Alguien", FALLBACK_LOCALE));
    }

    private static String displayName(User user) {
        String first = user.getName() == null ? "" : user.getName().trim();
        String last = user.getLastName() == null ? "" : user.getLastName().trim();
        String full = (first + " " + last).trim();
        return full.isEmpty() ? user.getEmail() : full;
    }

    /**
     * Builds the absolute URL that the email CTA points to. The active organization is held in
     * frontend state (no slug in the URL), so the link carries {@code ?org={id}} as a query
     * param: a dashboard-wide middleware reads it and switches the active org before the page
     * runs its fetches, so the deep link works even when the recipient has another org open.
     *
     * <p>Locations and piece types only have list pages today; pieces have a per-id detail
     * page. A DELETED resource has no detail to link to and falls back to the dashboard root.
     */
    private String buildResourceUrl(Integer orgId, ResourceKind kind, Integer resourceId, LifecycleAction effective) {
        String orgQuery = orgId == null ? "" : "?org=" + orgId;
        if (effective == LifecycleAction.DELETED) {
            return frontendBaseUrl + "/dashboard" + orgQuery;
        }
        return switch (kind) {
            case PIECE -> frontendBaseUrl + "/dashboard/articulos/"
                    + (resourceId == null ? "" : resourceId) + orgQuery;
            case LOCATION -> frontendBaseUrl + "/dashboard/ubicaciones" + orgQuery;
            case PIECE_TYPE -> frontendBaseUrl + "/dashboard/tipos-articulos" + orgQuery;
        };
    }

    private static String stripTrailingSlash(String value) {
        if (value == null) return "";
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
