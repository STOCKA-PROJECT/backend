package com.stocka.backend.modules.contacts.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.stocka.backend.modules.contacts.entity.Contact;
import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.users.entity.User;

@Repository
public interface ContactRepository extends JpaRepository<Contact, Integer> {
    List<Contact> findByOrganizationOrderByNameAscIdAsc(Organization organization);

    Optional<Contact> findByIdAndOrganization(Integer id, Organization organization);

    /**
     * Whether a (non-deleted) contact of {@code organization} already carries {@code email}
     * (case-insensitive). Per-org email uniqueness is enforced at the service layer, without a DB
     * UNIQUE constraint (email is optional and MariaDB cannot scope UNIQUE by {@code deleted_at}).
     */
    boolean existsByOrganizationAndEmailIgnoreCase(Organization organization, String email);

    /**
     * Same as {@link #existsByOrganizationAndEmailIgnoreCase} but excluding the contact with id
     * {@code excludeId} — used during update to allow re-saving the same value.
     */
    boolean existsByOrganizationAndEmailIgnoreCaseAndIdNot(
            Organization organization, String email, Integer excludeId);

    /**
     * Whether some (non-deleted) contact of {@code organization} is already linked to
     * {@code user}. At most one contact per organization may be linked to a given member.
     */
    boolean existsByOrganizationAndLinkedUser(Organization organization, User user);

    Optional<Contact> findFirstByOrganizationAndEmailIgnoreCase(Organization organization, String email);
}
