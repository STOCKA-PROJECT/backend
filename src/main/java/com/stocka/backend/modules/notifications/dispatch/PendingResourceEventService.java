package com.stocka.backend.modules.notifications.dispatch;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.stocka.backend.modules.notifications.dispatch.entity.PendingResourceEvent;
import com.stocka.backend.modules.notifications.dispatch.repository.PendingResourceEventRepository;
import com.stocka.backend.modules.notifications.events.ResourceLifecycleEvent;
import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.organizations.repository.OrganizationRepository;

/**
 * Upsert side of the notification queue. Called from
 * {@link ResourceLifecycleEventListener} after the originating transaction commits so a
 * rolled-back create/update/delete does not produce a phantom notification.
 *
 * <p>The first event for a given {@code (organization, resource_kind, resource_id)} tuple
 * inserts a row; every subsequent event in the quiet window updates {@code lastAction},
 * {@code lastEventAt} and the actor/owner/name snapshots, keeping {@code firstAction} and
 * {@code firstEventAt} untouched. A concurrent insert race resolves to an update so the
 * unique constraint never surfaces as a 500.
 */
@Service
public class PendingResourceEventService {

    private final PendingResourceEventRepository pendingRepository;
    private final OrganizationRepository organizationRepository;

    public PendingResourceEventService(
            PendingResourceEventRepository pendingRepository,
            OrganizationRepository organizationRepository
    ) {
        this.pendingRepository = pendingRepository;
        this.organizationRepository = organizationRepository;
    }

    @Transactional
    public void enqueue(ResourceLifecycleEvent event) {
        if (event == null || event.organizationId() == null
                || event.kind() == null || event.action() == null
                || event.resourceId() == null) {
            return;
        }
        Optional<Organization> orgOpt = organizationRepository.findById(event.organizationId());
        if (orgOpt.isEmpty()) {
            return;
        }
        Organization org = orgOpt.get();

        LocalDateTime now = LocalDateTime.now();
        Optional<PendingResourceEvent> existing = pendingRepository
                .findByOrganizationIdAndResourceKindAndResourceId(org.getId(), event.kind(), event.resourceId());
        try {
            if (existing.isPresent()) {
                applyUpdate(existing.get(), event, now);
                pendingRepository.save(existing.get());
            } else {
                pendingRepository.save(newRow(org, event, now));
            }
        } catch (DataIntegrityViolationException ex) {
            // Lost the insert race to a concurrent caller. Refetch and retry as an update.
            PendingResourceEvent winner = pendingRepository
                    .findByOrganizationIdAndResourceKindAndResourceId(org.getId(), event.kind(), event.resourceId())
                    .orElseThrow(() -> ex);
            applyUpdate(winner, event, now);
            pendingRepository.save(winner);
        }
    }

    private static PendingResourceEvent newRow(Organization org, ResourceLifecycleEvent event, LocalDateTime now) {
        return new PendingResourceEvent()
                .setOrganization(org)
                .setResourceKind(event.kind())
                .setResourceId(event.resourceId())
                .setFirstAction(event.action())
                .setLastAction(event.action())
                .setFirstEventAt(now)
                .setLastEventAt(now)
                .setActorUserId(event.actorUserId())
                .setOwnerUserId(event.ownerUserId())
                .setResourceName(event.resourceName());
    }

    private static void applyUpdate(PendingResourceEvent row, ResourceLifecycleEvent event, LocalDateTime now) {
        row.setLastAction(event.action());
        row.setLastEventAt(now);
        row.setActorUserId(event.actorUserId());
        row.setOwnerUserId(event.ownerUserId());
        if (event.resourceName() != null) {
            row.setResourceName(event.resourceName());
        }
    }
}
