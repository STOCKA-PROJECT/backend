package com.stocka.backend.modules.ports.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.ports.entity.Port;

/**
 * Data access for {@link Port}. All read methods return only active (not soft-deleted) rows thanks
 * to the {@code @SQLRestriction} on the entity.
 */
@Repository
public interface PortRepository extends JpaRepository<Port, Integer> {
    /**
     * Lists the ports of {@code organization} ordered by position then id.
     *
     * @param organization owning organization
     * @return ordered active ports
     */
    List<Port> findByOrganizationOrderByPositionAscIdAsc(Organization organization);

    /**
     * Finds an active port by its (organization, name) pair.
     *
     * @param organization owning organization
     * @param name         port name
     * @return the matching port, if any
     */
    Optional<Port> findByOrganizationAndName(Organization organization, String name);

    /**
     * Finds an active port by its (organization, pin) pair.
     *
     * @param organization owning organization
     * @param pin          Raspberry Pi GPIO pin number
     * @return the matching port, if any
     */
    Optional<Port> findByOrganizationAndPin(Organization organization, Integer pin);

    /**
     * Bulk soft-delete every still-active port of {@code organization}, also releasing its pin slot
     * by nulling {@code pin} (the {@code uk_port_org_pin} UNIQUE covers all rows regardless of
     * {@code deleted_at}). Used by the organization cascade so ports do not dangle when their
     * organization is removed.
     *
     * @param organization owner whose ports must be soft-deleted
     * @return number of rows affected
     */
    @Modifying
    @Query("update Port p set p.deletedAt = CURRENT_TIMESTAMP, p.pin = NULL "
            + "where p.organization = ?1 and p.deletedAt is null")
    int softDeleteByOrganization(Organization organization);
}
