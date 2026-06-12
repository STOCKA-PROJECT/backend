package com.stocka.backend.modules.timelines.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.stocka.backend.modules.organizations.service.OrganizationResolver;
import com.stocka.backend.modules.timelines.dto.TimelineSceneResponseDto;
import com.stocka.backend.modules.timelines.dto.UpsertTimelineSceneDto;
import com.stocka.backend.modules.timelines.entity.TimelineScene;
import com.stocka.backend.modules.timelines.service.TimelineSceneService;

/**
 * REST endpoints for the editor scene of a timeline.
 *
 * <p>Reads accept any organization member (including SPECTATOR); writes require OWNER, MANAGER or
 * USER — the same access rules as pieces and timelines.
 */
@RestController
@RequestMapping("/organizations/{orgSlug}/timelines/{timelineId}/scene")
public class TimelineSceneController {
    private final TimelineSceneService sceneService;
    private final OrganizationResolver orgResolver;

    public TimelineSceneController(TimelineSceneService sceneService, OrganizationResolver orgResolver) {
        this.sceneService = sceneService;
        this.orgResolver = orgResolver;
    }

    @GetMapping
    @PreAuthorize("@orgSecurity.canReadOrgContent(#orgSlug, principal)")
    public ResponseEntity<TimelineSceneResponseDto> get(
            @PathVariable String orgSlug,
            @PathVariable Integer timelineId
    ) {
        Integer orgId = orgResolver.requireCurrent(orgSlug).getId();
        return sceneService.find(orgId, timelineId)
                .map(scene -> ResponseEntity.ok(
                        TimelineSceneResponseDto.from(scene, sceneService.documentOf(scene))))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PutMapping
    @PreAuthorize("@orgSecurity.canWritePieces(#orgSlug, principal)")
    public ResponseEntity<TimelineSceneResponseDto> upsert(
            @PathVariable String orgSlug,
            @PathVariable Integer timelineId,
            @RequestBody UpsertTimelineSceneDto dto
    ) {
        Integer orgId = orgResolver.requireCurrent(orgSlug).getId();
        TimelineScene scene = sceneService.upsert(orgId, timelineId, dto);
        return ResponseEntity.ok(TimelineSceneResponseDto.from(scene, sceneService.documentOf(scene)));
    }
}
