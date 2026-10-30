package com.stocka.backend.modules.timelines.dto;

/**
 * Payload for {@code POST /organizations/{orgSlug}/timelines/{timelineId}/duplicate}. The body is
 * optional; without a {@code name} the copy gets the first free "&lt;name&gt; (còpia N)" in the
 * request language.
 */
public class DuplicateTimelineDto {
    private String name;

    public String getName() { return name; }
    public DuplicateTimelineDto setName(String v) { this.name = v; return this; }
}
