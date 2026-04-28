package com.stocka.backend.modules.locations.dto;

import com.stocka.backend.modules.locations.entity.Location;

/**
 * One step in a location's breadcrumb: the root appears first, the location itself last.
 */
public record LocationBreadcrumbItemDto(Integer id, String name) {
    public static LocationBreadcrumbItemDto from(Location location) {
        return new LocationBreadcrumbItemDto(location.getId(), location.getName());
    }
}
