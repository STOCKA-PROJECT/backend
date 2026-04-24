package com.stocka.backend.modules.organizations.dto;

public class UpdateOrganizationDto {
    private String name;
    private String slug;

    public String getName() {
        return name;
    }

    public UpdateOrganizationDto setName(String name) {
        this.name = name;
        return this;
    }

    public String getSlug() {
        return slug;
    }

    public UpdateOrganizationDto setSlug(String slug) {
        this.slug = slug;
        return this;
    }
}
