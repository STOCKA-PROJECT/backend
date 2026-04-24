package com.stocka.backend.modules.organizations.dto;

import com.stocka.backend.modules.organizations.entity.OrganizationRoleEnum;

public class CreateInvitationDto {
    private String email;
    private OrganizationRoleEnum role;

    public String getEmail() {
        return email;
    }

    public CreateInvitationDto setEmail(String email) {
        this.email = email;
        return this;
    }

    public OrganizationRoleEnum getRole() {
        return role;
    }

    public CreateInvitationDto setRole(OrganizationRoleEnum role) {
        this.role = role;
        return this;
    }
}
