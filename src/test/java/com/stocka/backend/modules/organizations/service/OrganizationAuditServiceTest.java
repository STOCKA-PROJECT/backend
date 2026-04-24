package com.stocka.backend.modules.organizations.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.stocka.backend.modules.organizations.entity.AuditAction;
import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.organizations.entity.OrganizationAuditLog;
import com.stocka.backend.modules.organizations.repository.OrganizationAuditLogRepository;
import com.stocka.backend.modules.users.entity.User;

@ExtendWith(MockitoExtension.class)
@DisplayName("OrganizationAuditService")
class OrganizationAuditServiceTest {

    @Mock private OrganizationAuditLogRepository repository;

    @InjectMocks private OrganizationAuditService sut;

    @Test
    @DisplayName("should persist all fields including serialized JSON payload")
    void should_persistAllFields() {
        Organization org = new Organization().setId(1).setName("Acme").setSlug("acme");
        User actor = new User().setId(10).setEmail("a@test.com");
        User target = new User().setId(20).setEmail("t@test.com");
        Map<String, Object> payload = Map.of("oldRole", "USER", "newRole", "MANAGER");
        when(repository.save(any(OrganizationAuditLog.class))).thenAnswer(inv -> inv.getArgument(0));
        ArgumentCaptor<OrganizationAuditLog> captor = ArgumentCaptor.forClass(OrganizationAuditLog.class);

        sut.log(org, actor, AuditAction.MEMBER_ROLE_CHANGED, target, payload);

        verify(repository).save(captor.capture());
        OrganizationAuditLog saved = captor.getValue();
        assertEquals(org, saved.getOrganization());
        assertEquals(actor, saved.getActor());
        assertEquals(AuditAction.MEMBER_ROLE_CHANGED, saved.getAction());
        assertEquals(target, saved.getTargetUser());
        assertNotNull(saved.getPayload());
        assertTrue(saved.getPayload().contains("oldRole"));
        assertTrue(saved.getPayload().contains("MANAGER"));
    }

    @Test
    @DisplayName("should accept null actor and null target")
    void should_acceptNullActorAndTarget() {
        when(repository.save(any(OrganizationAuditLog.class))).thenAnswer(inv -> inv.getArgument(0));
        ArgumentCaptor<OrganizationAuditLog> captor = ArgumentCaptor.forClass(OrganizationAuditLog.class);

        sut.log(new Organization().setId(1), null, AuditAction.ORG_DELETED, null, null);

        verify(repository).save(captor.capture());
        assertNull(captor.getValue().getActor());
        assertNull(captor.getValue().getTargetUser());
        assertNull(captor.getValue().getPayload());
    }

    @Test
    @DisplayName("should serialize empty payload as null")
    void should_treatEmptyPayloadAsNull() {
        when(repository.save(any(OrganizationAuditLog.class))).thenAnswer(inv -> inv.getArgument(0));
        ArgumentCaptor<OrganizationAuditLog> captor = ArgumentCaptor.forClass(OrganizationAuditLog.class);

        sut.log(new Organization().setId(1), null, AuditAction.ORG_CREATED, null, Map.of());

        verify(repository).save(captor.capture());
        assertNull(captor.getValue().getPayload());
    }
}
