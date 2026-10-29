package com.stocka.backend.modules.notifications.dispatch;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.stocka.backend.modules.notifications.dispatch.entity.NotificationOutboxEntry;
import com.stocka.backend.modules.notifications.dispatch.repository.NotificationOutboxRepository;
import com.stocka.backend.modules.notifications.email.ActivityDigestItem;
import com.stocka.backend.modules.notifications.email.EmailService;
import com.stocka.backend.modules.notifications.preferences.entity.NotificationDelivery;
import com.stocka.backend.modules.notifications.preferences.entity.NotificationPreference;
import com.stocka.backend.modules.notifications.preferences.service.NotificationPreferenceService;
import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.organizations.repository.OrganizationMemberRepository;
import com.stocka.backend.modules.organizations.repository.OrganizationRepository;
import com.stocka.backend.modules.users.entity.Language;
import com.stocka.backend.modules.users.entity.User;
import com.stocka.backend.modules.users.repository.UserRepository;

/**
 * Turns the unsent outbox rows of one (recipient, organization) group into a single digest
 * email. Each group runs in its own transaction so a failure only leaves that group pending;
 * the rows stay unsent and are retried on the next trigger with the same batch key, which the
 * Resend provider turns into an idempotency key.
 *
 * <p>Which trigger sends a group depends on the recipient's current delivery mode, read at
 * send time so a change of preference applies to what is already waiting:
 * <ul>
 *   <li>{@code INSTANT}: sent at the next tick (rows parked before the user switched modes);</li>
 *   <li>{@code BATCHED}: sent at a tick once the newest event of the group is older than
 *   {@code app.notifications.batch-window-ms};</li>
 *   <li>{@code DAILY}: sent only by the {@link Trigger#DAILY} run.</li>
 * </ul>
 *
 * @since 2026-10-29
 */
@Service
public class NotificationDigestDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDigestDispatcher.class);

    /** What woke the dispatcher up. */
    public enum Trigger {
        /** The periodic tick that also drains the per-resource queue. */
        TICK,
        /** The daily cron. */
        DAILY
    }

    private final NotificationOutboxRepository outboxRepository;
    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final OrganizationMemberRepository memberRepository;
    private final NotificationPreferenceService preferenceService;
    private final EmailService emailService;
    private final NotificationDispatchProperties properties;
    private final String frontendBaseUrl;

    public NotificationDigestDispatcher(
            NotificationOutboxRepository outboxRepository,
            UserRepository userRepository,
            OrganizationRepository organizationRepository,
            OrganizationMemberRepository memberRepository,
            NotificationPreferenceService preferenceService,
            EmailService emailService,
            NotificationDispatchProperties properties,
            @Value("${app.frontend.base-url:http://localhost:3002}") String frontendBaseUrl
    ) {
        this.outboxRepository = outboxRepository;
        this.userRepository = userRepository;
        this.organizationRepository = organizationRepository;
        this.memberRepository = memberRepository;
        this.preferenceService = preferenceService;
        this.emailService = emailService;
        this.properties = properties;
        this.frontendBaseUrl = PendingResourceEventDispatcher.stripTrailingSlash(frontendBaseUrl);
    }

    /**
     * Sends the digest of one group when its delivery mode and the trigger allow it.
     *
     * @param userId         recipient
     * @param organizationId organization
     * @param newestEventAt  time of the newest unsent event of the group (from the grouping query)
     * @param trigger        what woke the dispatcher up
     * @param now            reference time for the batch window
     * @return {@code true} when an email left (or the group was discarded), {@code false} when it
     *         stays pending
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean processGroup(Integer userId, Integer organizationId, LocalDateTime newestEventAt,
                                Trigger trigger, LocalDateTime now) {
        List<NotificationOutboxEntry> entries = outboxRepository
                .findByUserIdAndOrganizationIdAndSentAtIsNullOrderByEventAtAscIdAsc(userId, organizationId);
        if (entries.isEmpty()) {
            return true;
        }

        Optional<User> userOpt = userRepository.findById(userId);
        Optional<Organization> orgOpt = organizationRepository.findById(organizationId);
        if (userOpt.isEmpty() || orgOpt.isEmpty() || orgOpt.get().getDeletedAt() != null) {
            discard(entries, "recipient or organization gone");
            return true;
        }
        User recipient = userOpt.get();
        Organization org = orgOpt.get();
        if (memberRepository.findByUserAndOrganization(recipient, org).isEmpty()) {
            discard(entries, "recipient is no longer a member");
            return true;
        }
        if (recipient.getEmail() == null || !recipient.isEmailVerified()) {
            discard(entries, "recipient has no verified email");
            return true;
        }

        NotificationPreference pref = preferenceService.resolveOrDefault(recipient, org);
        NotificationDelivery delivery = pref == null || pref.getDelivery() == null
                ? NotificationDelivery.BATCHED : pref.getDelivery();
        if (!isDue(delivery, trigger, newestEventAt, now)) {
            return false;
        }

        ZoneId zone = properties.zoneId();
        List<ActivityDigestItem> items = entries.stream()
                .map(e -> new ActivityDigestItem(
                        e.getResourceKind(),
                        e.getAction(),
                        e.getResourceName(),
                        e.getResourceUrl(),
                        e.getActorName(),
                        toZone(e.getEventAt(), zone)))
                .toList();
        String batchKey = organizationId + "/" + entries.getFirst().getId() + "-" + entries.getLast().getId()
                + "/" + entries.size();

        emailService.sendActivityDigestEmail(
                recipient.getEmail(),
                org.getName(),
                PendingResourceEventDispatcher.organizationUrl(frontendBaseUrl, org.getSlug()),
                items,
                batchKey,
                Optional.ofNullable(recipient.getLanguage()).orElse(Language.ES));

        for (NotificationOutboxEntry entry : entries) {
            entry.setSentAt(now);
        }
        outboxRepository.saveAll(entries);
        log.info("notification_digest_sent user={} org={} events={} trigger={}",
                userId, organizationId, entries.size(), trigger);
        return true;
    }

    /**
     * Removes sent rows older than the retention period.
     *
     * @param now reference time
     * @return rows removed
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int purgeSent(LocalDateTime now) {
        return outboxRepository.deleteSentBefore(now.minusDays(properties.retentionDays()));
    }

    /**
     * Whether a group with the given delivery mode must leave on this trigger.
     *
     * @param delivery      recipient's delivery mode
     * @param trigger       what woke the dispatcher up
     * @param newestEventAt newest unsent event of the group
     * @param now           reference time
     * @return {@code true} when the digest must be sent now
     */
    boolean isDue(NotificationDelivery delivery, Trigger trigger, LocalDateTime newestEventAt, LocalDateTime now) {
        return switch (delivery) {
            case INSTANT -> trigger == Trigger.TICK;
            case BATCHED -> trigger == Trigger.TICK
                    && newestEventAt != null
                    && !newestEventAt.isAfter(now.minus(Duration.ofMillis(properties.batchWindowMs())));
            case DAILY -> trigger == Trigger.DAILY;
        };
    }

    private void discard(List<NotificationOutboxEntry> entries, String reason) {
        log.info("notification_digest_discarded events={} reason={}", entries.size(), reason);
        outboxRepository.deleteAll(entries);
    }

    private static LocalDateTime toZone(LocalDateTime at, ZoneId zone) {
        if (at == null) return null;
        return at.atZone(ZoneId.systemDefault()).withZoneSameInstant(zone).toLocalDateTime();
    }
}
