package com.stocka.backend.modules.locations.dto;

import java.util.List;

import com.stocka.backend.modules.locations.entity.Location;

/**
 * Recursive view used by {@code GET /organizations/{orgId}/locations/tree}. Each node contains
 * its direct children, fully materialized.
 */
public record LocationTreeNodeDto(
        Integer id,
        String name,
        String description,
        List<LocationTreeNodeDto> children
) {
    public static LocationTreeNodeDto from(Location location, List<LocationTreeNodeDto> children) {
        return new LocationTreeNodeDto(location.getId(), location.getName(), location.getDescription(), children);
    }
}
