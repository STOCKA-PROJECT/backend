package com.stocka.backend.modules.notifications.dispatch.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import com.stocka.backend.modules.notifications.dispatch.entity.PendingResourceEvent;
import com.stocka.backend.modules.notifications.events.ResourceKind;

@Repository
public interface PendingResourceEventRepository extends CrudRepository<PendingResourceEvent, Integer> {

    Optional<PendingResourceEvent> findByOrganizationIdAndResourceKindAndResourceId(
            Integer organizationId, ResourceKind resourceKind, Integer resourceId);

    @Query("select p.id from PendingResourceEvent p "
            + "where p.lastEventAt <= ?1 "
            + "order by p.lastEventAt asc")
    List<Integer> findDueIds(LocalDateTime threshold, Pageable pageable);
}
