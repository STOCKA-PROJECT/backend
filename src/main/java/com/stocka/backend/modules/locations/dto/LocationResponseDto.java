package com.stocka.backend.modules.locations.dto;

import java.util.Date;
import java.util.List;

import com.stocka.backend.modules.locations.entity.Location;

/**
 * Flat representation of a location returned by REST endpoints. Includes optional breadcrumb
 * (root → ... → self) when callers ask for a single location.
 */
public record LocationResponseDto(
        Integer id,
        Integer organizationId,
        String name,
        String description,
        Integer parentId,
        Date createdAt,
        Date updatedAt,
        List<LocationBreadcrumbItemDto> breadcrumb
) {
    public static LocationResponseDto from(Location location, List<LocationBreadcrumbItemDto> breadcrumb) {
        return new LocationResponseDto(
                location.getId(),
                location.getOrganization().getId(),
                location.getName(),
                location.getDescription(),
                location.getParent() == null ? null : location.getParent().getId(),
                location.getCreatedAt(),
                location.getUpdatedAt(),
                breadcrumb
        );
    }

    public static LocationResponseDto from(Location location) {
        return from(location, null);
    }
}
