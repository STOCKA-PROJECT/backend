package com.stocka.backend.modules.devices.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.stocka.backend.modules.devices.entity.Device;
import com.stocka.backend.modules.organizations.entity.Organization;

/**
 * Data access for {@link Device}. All read methods return only active (not soft-deleted) rows
 * thanks to the {@code @SQLRestriction} on the entity.
 */
@Repository
public interface DeviceRepository extends JpaRepository<Device, Integer> {
    /**
     * Lists the devices of {@code organization} ordered by name then id.
     *
     * @param organization owning organization
     * @return ordered active devices
     */
    List<Device> findByOrganizationOrderByNameAscIdAsc(Organization organization);

    /**
     * Finds an active device by its (organization, name) pair.
     *
     * @param organization owning organization
     * @param name         device name
     * @return the matching device, if any
     */
    Optional<Device> findByOrganizationAndName(Organization organization, String name);

    /**
     * Finds the active device whose heartbeat token hashes to {@code tokenHash}. The lookup has no
     * organization because the player only presents the token.
     *
     * @param tokenHash lowercase sha256 hex of the token
     * @return the matching device, if any
     */
    Optional<Device> findByTokenHash(String tokenHash);

    /**
     * Bulk soft-delete every still-active device of {@code organization}. Used by the organization
     * cascade so devices do not dangle when their organization is removed. Names are not mangled:
     * the UNIQUE includes the organization, which is gone, and its slug is released anyway.
     *
     * @param organization owner whose devices must be soft-deleted
     * @return number of rows affected
     */
    @Modifying
    @Query("update Device d set d.deletedAt = CURRENT_TIMESTAMP "
            + "where d.organization = ?1 and d.deletedAt is null")
    int softDeleteByOrganization(Organization organization);
}
