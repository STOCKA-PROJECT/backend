package com.stocka.backend.modules.organizations.dto;

public class CreateOrganizationDto {
    private String name;
    private String slug;

    public String getName() {
        return name;
    }

    public CreateOrganizationDto setName(String name) {
        this.name = name;
        return this;
    }

    public String getSlug() {
        return slug;
    }

    public CreateOrganizationDto setSlug(String slug) {
        this.slug = slug;
        return this;
    }
}
