package com.stocka.backend.modules.organizations.dto;

import com.stocka.backend.modules.organizations.entity.OrganizationMember;
import com.stocka.backend.modules.organizations.entity.OrganizationRoleEnum;

public class MemberResponseDto {
    private Integer id;
    private Integer userId;
    private String name;
    private String lastName;
    private String email;
    private OrganizationRoleEnum role;

    public static MemberResponseDto from(OrganizationMember member) {
        MemberResponseDto dto = new MemberResponseDto();
        dto.id = member.getId();
        dto.userId = member.getUser().getId();
        dto.name = member.getUser().getName();
        dto.lastName = member.getUser().getLastName();
        dto.email = member.getUser().getEmail();
        dto.role = member.getRole();
        return dto;
    }

    public Integer getId() {
        return id;
    }

    public Integer getUserId() {
        return userId;
    }

    public String getName() {
        return name;
    }

    public String getLastName() {
        return lastName;
    }

    public String getEmail() {
        return email;
    }

    public OrganizationRoleEnum getRole() {
        return role;
    }
}
