package com.stocka.backend.modules.organizations.dto;

import java.time.LocalDateTime;
import java.time.ZoneId;

import com.stocka.backend.modules.organizations.entity.InvitationStatus;
import com.stocka.backend.modules.organizations.entity.OrganizationInvitation;
import com.stocka.backend.modules.organizations.entity.OrganizationRoleEnum;

public class InvitationResponseDto {
    private Integer id;
    private String email;
    private OrganizationRoleEnum role;
    private InvitationStatus status;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime acceptedAt;
    private String token;
    private OrganizationSummary organization;

    public static InvitationResponseDto from(OrganizationInvitation inv, boolean includeToken) {
        InvitationResponseDto dto = new InvitationResponseDto();
        dto.id = inv.getId();
        dto.email = inv.getEmail();
        dto.role = inv.getRole();
        dto.status = inv.getStatus();
        dto.expiresAt = inv.getExpiresAt();
        dto.createdAt = inv.getCreatedAt() == null
                ? null
                : inv.getCreatedAt().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
        dto.acceptedAt = inv.getAcceptedAt();
        dto.token = includeToken ? inv.getToken() : null;
        dto.organization = new OrganizationSummary(
                inv.getOrganization().getId(),
                inv.getOrganization().getName(),
                inv.getOrganization().getSlug()
        );
        return dto;
    }

    public Integer getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public OrganizationRoleEnum getRole() {
        return role;
    }

    public InvitationStatus getStatus() {
        return status;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getAcceptedAt() {
        return acceptedAt;
    }

    public String getToken() {
        return token;
    }

    public OrganizationSummary getOrganization() {
        return organization;
    }

    public record OrganizationSummary(Integer id, String name, String slug) {
    }
}
