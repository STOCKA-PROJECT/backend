package com.stocka.backend.modules.organizations.repository;

import java.util.List;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.organizations.entity.OrganizationAuditLog;

@Repository
public interface OrganizationAuditLogRepository extends CrudRepository<OrganizationAuditLog, Long> {
    List<OrganizationAuditLog> findByOrganizationOrderByCreatedAtDesc(Organization organization);
}
