package com.stocka.backend.modules.organizations.dto;

import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.organizations.entity.OrganizationRoleEnum;

public class OrganizationResponseDto {
    private Integer id;
    private String name;
    private String slug;
    private OrganizationRoleEnum currentUserRole;
    private boolean pieceTypeActionsEnabled;

    public static OrganizationResponseDto from(Organization org, OrganizationRoleEnum currentUserRole) {
        return from(org, currentUserRole, false);
    }

    /**
     * Builds the response including the piece-type actions capability flag.
     *
     * @param org                     source organization
     * @param currentUserRole         caller's role in the organization, or {@code null}
     * @param pieceTypeActionsEnabled whether the private actions feature is available to the caller
     * @return the populated DTO
     */
    public static OrganizationResponseDto from(
            Organization org,
            OrganizationRoleEnum currentUserRole,
            boolean pieceTypeActionsEnabled
    ) {
        OrganizationResponseDto dto = new OrganizationResponseDto();
        dto.id = org.getId();
        dto.name = org.getName();
        dto.slug = org.getSlug();
        dto.currentUserRole = currentUserRole;
        dto.pieceTypeActionsEnabled = pieceTypeActionsEnabled;
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

    public boolean isPieceTypeActionsEnabled() {
        return pieceTypeActionsEnabled;
    }
}
