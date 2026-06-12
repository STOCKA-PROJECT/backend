package com.stocka.backend.modules.timelines.dto;

/**
 * Payload for {@code POST /organizations/{orgSlug}/timelines}.
 */
public class CreateTimelineDto {
    private String name;

    public String getName() { return name; }
    public CreateTimelineDto setName(String v) { this.name = v; return this; }
}
