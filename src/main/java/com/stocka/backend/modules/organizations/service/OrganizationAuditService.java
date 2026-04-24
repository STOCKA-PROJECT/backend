package com.stocka.backend.modules.organizations.service;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.stocka.backend.modules.organizations.entity.AuditAction;
import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.organizations.entity.OrganizationAuditLog;
import com.stocka.backend.modules.organizations.repository.OrganizationAuditLogRepository;
import com.stocka.backend.modules.users.entity.User;

@Service
public class OrganizationAuditService {
    private static final Logger log = LoggerFactory.getLogger(OrganizationAuditService.class);

    private final OrganizationAuditLogRepository repository;
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    public OrganizationAuditService(OrganizationAuditLogRepository repository) {
        this.repository = repository;
    }

    public OrganizationAuditLog log(
            Organization organization,
            User actor,
            AuditAction action,
            User targetUser,
            Map<String, Object> payload
    ) {
        String json = serialize(payload);
        OrganizationAuditLog entry = new OrganizationAuditLog()
                .setOrganization(organization)
                .setActor(actor)
                .setAction(action)
                .setTargetUser(targetUser)
                .setPayload(json);
        return repository.save(entry);
    }

    private String serialize(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JacksonException e) {
            log.warn("Could not serialize audit payload: {}", e.getMessage());
            return null;
        }
    }
}
