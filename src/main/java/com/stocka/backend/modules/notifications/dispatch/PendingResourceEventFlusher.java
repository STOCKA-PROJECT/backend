package com.stocka.backend.modules.notifications.dispatch;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
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
 * Drains the pending-event queue once the quiet window has elapsed. Each row produces at
 * most one email per eligible recipient; the effective action is reduced from
 * (firstAction, lastAction) so transient resources (created and deleted inside the
 * window) trigger nothing at all. Recipient preferences are evaluated at flush time so a
 * preference change made during the window is honored.
 */
@Component
public class PendingResourceEventFlusher {
    private static final Logger log = LoggerFactory.getLogger(PendingResourceEventFlusher.class);
    private static final Locale FALLBACK_LOCALE = Locale.of("es");

    private final PendingResourceEventRepository pendingRepository;
    private final OrganizationMemberRepository memberRepository;
    private final UserRepository userRepository;
    private final NotificationPreferenceService preferenceService;
    private final EmailService emailService;
    private final NotificationDispatchProperties properties;
    private final MessageSource messageSource;
    private final String frontendBaseUrl;

    public PendingResourceEventFlusher(
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

    @Scheduled(fixedDelayString = "${app.notifications.flush-interval-ms:15000}")
    public void flushDue() {
        LocalDateTime threshold = LocalDateTime.now().minus(Duration.ofMillis(properties.windowMs()));
        List<Integer> dueIds = pendingRepository.findDueIds(threshold,
                PageRequest.of(0, Math.max(1, properties.batchSize())));
        for (Integer id : dueIds) {
            try {
                processOne(id);
            } catch (RuntimeException ex) {
                // The REQUIRES_NEW transaction has already rolled back; log and move on so
                // a single misbehaving row never blocks the queue.
                log.warn("Failed to flush pending resource event id={}: {}", id, ex.getMessage());
                incrementOrDrop(id);
            }
        }
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
        String resourceUrl = buildResourceUrl(org, row.getResourceKind(), row.getResourceId(), effective);

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
                .map(PendingResourceEventFlusher::displayName)
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

    private String buildResourceUrl(Organization org, ResourceKind kind, Integer resourceId, LifecycleAction effective) {
        if (effective == LifecycleAction.DELETED) {
            return frontendBaseUrl + "/dashboard";
        }
        String slug = org.getSlug() == null ? "" : org.getSlug();
        String section = switch (kind) {
            case PIECE -> "/articulos/";
            case LOCATION -> "/ubicaciones/";
            case PIECE_TYPE -> "/tipos/";
        };
        return frontendBaseUrl + "/dashboard/o/" + slug + section + (resourceId == null ? "" : resourceId);
    }

    private static String stripTrailingSlash(String value) {
        if (value == null) return "";
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
