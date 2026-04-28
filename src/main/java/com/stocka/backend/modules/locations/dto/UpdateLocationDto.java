package com.stocka.backend.modules.locations.dto;

/**
 * Payload for {@code PATCH /organizations/{orgId}/locations/{locationId}}.
 *
 * <p>All three fields are optional and follow PATCH-partial semantics: a {@code null} value means
 * "leave unchanged". To clear {@code description} send an empty string. To detach a location to
 * the root of the tree the dedicated flag {@link #setMoveToRoot(Boolean)} must be set to true,
 * because a {@code null} {@code parentId} would otherwise be ambiguous with "leave unchanged".
 */
public class UpdateLocationDto {
    private String name;
    private String description;
    private Integer parentId;
    private Boolean moveToRoot;

    public String getName() {
        return name;
    }

    public UpdateLocationDto setName(String name) {
        this.name = name;
        return this;
    }

    public String getDescription() {
        return description;
    }

    public UpdateLocationDto setDescription(String description) {
        this.description = description;
        return this;
    }

    public Integer getParentId() {
        return parentId;
    }

    public UpdateLocationDto setParentId(Integer parentId) {
        this.parentId = parentId;
        return this;
    }

    public Boolean getMoveToRoot() {
        return moveToRoot;
    }

    public UpdateLocationDto setMoveToRoot(Boolean moveToRoot) {
        this.moveToRoot = moveToRoot;
        return this;
    }
}
