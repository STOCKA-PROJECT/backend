package com.stocka.backend.modules.organizations.dto;

import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.organizations.entity.OrganizationRoleEnum;

public class OrganizationResponseDto {
    private Integer id;
    private String name;
    private String slug;
    private OrganizationRoleEnum currentUserRole;
    private boolean pieceTypeActionsEnabled;
    private boolean portsEnabled;

    public static OrganizationResponseDto from(Organization org, OrganizationRoleEnum currentUserRole) {
        return from(org, currentUserRole, false, false);
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
        return from(org, currentUserRole, pieceTypeActionsEnabled, false);
    }

    /**
     * Builds the response including both private-feature capability flags.
     *
     * @param org                     source organization
     * @param currentUserRole         caller's role in the organization, or {@code null}
     * @param pieceTypeActionsEnabled whether the private actions feature is available to the caller
     * @param portsEnabled            whether the private ports feature is available to the caller
     * @return the populated DTO
     */
    public static OrganizationResponseDto from(
            Organization org,
            OrganizationRoleEnum currentUserRole,
            boolean pieceTypeActionsEnabled,
            boolean portsEnabled
    ) {
        OrganizationResponseDto dto = new OrganizationResponseDto();
        dto.id = org.getId();
        dto.name = org.getName();
        dto.slug = org.getSlug();
        dto.currentUserRole = currentUserRole;
        dto.pieceTypeActionsEnabled = pieceTypeActionsEnabled;
        dto.portsEnabled = portsEnabled;
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

    public boolean isPortsEnabled() {
        return portsEnabled;
    }
}
