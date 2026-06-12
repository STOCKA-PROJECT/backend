package com.stocka.backend.modules.timelines.dto;

/**
 * Payload for {@code PATCH /organizations/{orgSlug}/timelines/{timelineId}}. All fields are
 * optional; only the supplied ones are applied.
 */
public class UpdateTimelineDto {
    private String name;

    public String getName() { return name; }
    public UpdateTimelineDto setName(String v) { this.name = v; return this; }
}
