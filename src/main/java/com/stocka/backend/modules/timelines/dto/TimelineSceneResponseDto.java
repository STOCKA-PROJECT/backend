package com.stocka.backend.modules.timelines.dto;

import java.util.Date;

import com.stocka.backend.modules.timelines.entity.TimelineScene;

import tools.jackson.databind.JsonNode;

/**
 * Representation of a timeline's editor scene. {@code document} is the parsed JSON tree (or
 * {@code null} when the scene has never been saved).
 */
public record TimelineSceneResponseDto(
        Integer id,
        Integer timelineId,
        int schemaVersion,
        JsonNode document,
        int version,
        Date createdAt,
        Date updatedAt
) {
    public static TimelineSceneResponseDto from(TimelineScene scene, JsonNode document) {
        return new TimelineSceneResponseDto(
                scene.getId(),
                scene.getTimeline().getId(),
                scene.getSchemaVersion(),
                document,
                scene.getVersion(),
                scene.getCreatedAt(),
                scene.getUpdatedAt()
        );
    }
}
