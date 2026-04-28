package com.stocka.backend.modules.locations.dto;

/**
 * Payload for {@code POST /organizations/{orgId}/locations}.
 *
 * <p>{@code parentId} may be {@code null} to create a root-level location.
 */
public class CreateLocationDto {
    private String name;
    private String description;
    private Integer parentId;

    public String getName() {
        return name;
    }

    public CreateLocationDto setName(String name) {
        this.name = name;
        return this;
    }

    public String getDescription() {
        return description;
    }

    public CreateLocationDto setDescription(String description) {
        this.description = description;
        return this;
    }

    public Integer getParentId() {
        return parentId;
    }

    public CreateLocationDto setParentId(Integer parentId) {
        this.parentId = parentId;
        return this;
    }
}
