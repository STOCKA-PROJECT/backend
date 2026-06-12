package com.stocka.backend.modules.timelines.dto;

import java.util.Date;

import com.stocka.backend.modules.timelines.entity.Timeline;

/**
 * Flat representation of a timeline returned by REST endpoints.
 */
public record TimelineResponseDto(
        Integer id,
        Integer organizationId,
        String name,
        Date createdAt,
        Date updatedAt
) {
    public static TimelineResponseDto from(Timeline timeline) {
        return new TimelineResponseDto(
                timeline.getId(),
                timeline.getOrganization().getId(),
                timeline.getName(),
                timeline.getCreatedAt(),
                timeline.getUpdatedAt()
        );
    }
}
