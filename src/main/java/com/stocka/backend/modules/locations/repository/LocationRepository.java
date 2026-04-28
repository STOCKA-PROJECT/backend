package com.stocka.backend.modules.locations.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import com.stocka.backend.modules.locations.entity.Location;
import com.stocka.backend.modules.organizations.entity.Organization;

@Repository
public interface LocationRepository extends CrudRepository<Location, Integer> {
    List<Location> findByOrganization(Organization organization);

    List<Location> findByOrganizationAndParentIsNull(Organization organization);

    List<Location> findByParent(Location parent);

    long countByParent(Location parent);

    Optional<Location> findByOrganizationAndParentAndName(Organization organization, Location parent, String name);

    Optional<Location> findByOrganizationAndParentIsNullAndName(Organization organization, String name);
}
