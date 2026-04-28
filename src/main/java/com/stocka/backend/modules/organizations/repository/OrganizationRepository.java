package com.stocka.backend.modules.organizations.repository;

import java.util.Optional;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import com.stocka.backend.modules.organizations.entity.Organization;

@Repository
public interface OrganizationRepository extends CrudRepository<Organization, Integer> {
    Optional<Organization> findBySlug(String slug);
    boolean existsBySlug(String slug);
}
