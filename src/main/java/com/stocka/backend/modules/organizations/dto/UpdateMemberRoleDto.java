package com.stocka.backend.modules.organizations.dto;

import com.stocka.backend.modules.organizations.entity.OrganizationRoleEnum;

public class UpdateMemberRoleDto {
    private OrganizationRoleEnum role;

    public OrganizationRoleEnum getRole() {
        return role;
    }

    public UpdateMemberRoleDto setRole(OrganizationRoleEnum role) {
        this.role = role;
        return this;
    }
}
