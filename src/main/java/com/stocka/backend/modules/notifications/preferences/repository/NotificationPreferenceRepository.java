package com.stocka.backend.modules.notifications.preferences.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import com.stocka.backend.modules.notifications.preferences.entity.NotificationPreference;
import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.users.entity.User;

@Repository
public interface NotificationPreferenceRepository extends CrudRepository<NotificationPreference, Integer> {

    Optional<NotificationPreference> findByUserAndOrganization(User user, Organization organization);

    List<NotificationPreference> findByUserAndOrganizationIn(User user, Collection<Organization> organizations);

    List<NotificationPreference> findByUser(User user);

    @Modifying
    @Query("update NotificationPreference p set p.deletedAt = CURRENT_TIMESTAMP "
            + "where p.user = ?1 and p.organization = ?2 and p.deletedAt is null")
    int softDeleteByUserAndOrganization(User user, Organization organization);

    @Modifying
    @Query("update NotificationPreference p set p.deletedAt = CURRENT_TIMESTAMP "
            + "where p.user = ?1 and p.deletedAt is null")
    int softDeleteByUser(User user);

    @Modifying
    @Query("update NotificationPreference p set p.deletedAt = CURRENT_TIMESTAMP "
            + "where p.organization = ?1 and p.deletedAt is null")
    int softDeleteByOrganization(Organization organization);
}
