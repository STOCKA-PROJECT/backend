package com.stocka.backend.modules.organizations.dto;

import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.organizations.entity.OrganizationRoleEnum;

public class OrganizationResponseDto {
    private Integer id;
    private String name;
    private String slug;
    private OrganizationRoleEnum currentUserRole;

    public static OrganizationResponseDto from(Organization org, OrganizationRoleEnum currentUserRole) {
        OrganizationResponseDto dto = new OrganizationResponseDto();
        dto.id = org.getId();
        dto.name = org.getName();
        dto.slug = org.getSlug();
        dto.currentUserRole = currentUserRole;
        return dto;
    }

    public Integer getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getSlug() {
        return slug;
    }

    public OrganizationRoleEnum getCurrentUserRole() {
        return currentUserRole;
    }
}
