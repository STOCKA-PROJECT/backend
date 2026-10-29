package com.stocka.backend.modules.notifications.dispatch.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import com.stocka.backend.modules.notifications.dispatch.entity.NotificationOutboxEntry;

/**
 * Access to the digest outbox. Unsent rows are grouped by recipient and organization; a group is
 * the unit the digest flusher turns into one email.
 *
 * @since 2026-10-29
 */
@Repository
public interface NotificationOutboxRepository extends CrudRepository<NotificationOutboxEntry, Integer> {

    /**
     * One row per (recipient, organization) with unsent events, with the newest event time of
     * the group, oldest group first.
     *
     * @return tuples {@code [userId, organizationId, maxEventAt]}
     */
    @Query("select e.user.id, e.organization.id, max(e.eventAt) from NotificationOutboxEntry e "
            + "where e.sentAt is null "
            + "group by e.user.id, e.organization.id "
            + "order by max(e.eventAt) asc")
    List<Object[]> findPendingGroups();

    List<NotificationOutboxEntry> findByUserIdAndOrganizationIdAndSentAtIsNullOrderByEventAtAscIdAsc(
            Integer userId, Integer organizationId);

    @Modifying
    @Query("delete from NotificationOutboxEntry e where e.sentAt is not null and e.sentAt < ?1")
    int deleteSentBefore(LocalDateTime threshold);
}
