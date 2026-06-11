package com.stocka.backend.modules.timelines.dto;

import tools.jackson.databind.JsonNode;

/**
 * Body of {@code PUT /organizations/{orgSlug}/timelines/{timelineId}/scene}. Carries the full editor
 * {@code document} tree plus the {@code version} the client last loaded (for optimistic concurrency;
 * {@code null} on the very first save).
 */
public class UpsertTimelineSceneDto {
    private Integer schemaVersion;
    private JsonNode document;
    private Integer version;

    public Integer getSchemaVersion() { return schemaVersion; }
    public UpsertTimelineSceneDto setSchemaVersion(Integer v) { this.schemaVersion = v; return this; }

    public JsonNode getDocument() { return document; }
    public UpsertTimelineSceneDto setDocument(JsonNode v) { this.document = v; return this; }

    public Integer getVersion() { return version; }
    public UpsertTimelineSceneDto setVersion(Integer v) { this.version = v; return this; }
}
