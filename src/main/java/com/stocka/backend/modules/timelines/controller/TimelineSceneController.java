package com.stocka.backend.modules.timelines.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.stocka.backend.modules.organizations.service.OrganizationResolver;
import com.stocka.backend.modules.timelines.dto.SceneEditResponseDto;
import com.stocka.backend.modules.timelines.dto.SceneOpsRequestDto;
import com.stocka.backend.modules.timelines.dto.SceneValidationDto;
import com.stocka.backend.modules.timelines.dto.TimelineSceneResponseDto;
import com.stocka.backend.modules.timelines.dto.UpsertTimelineSceneDto;
import com.stocka.backend.modules.timelines.entity.TimelineScene;
import com.stocka.backend.modules.timelines.scene.SceneIssue;
import com.stocka.backend.modules.timelines.scene.SceneSummary;
import com.stocka.backend.modules.timelines.service.TimelineSceneService;

/**
 * REST endpoints for the editor scene of a timeline: the whole document ({@code GET}/{@code PUT}),
 * server-side operations ({@code POST /ops}), a compact summary and a validation report.
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

    /**
     * Applies a batch of operations atomically (see {@code SceneOp}) and returns the saved scene,
     * the ids it created, validation findings and a summary.
     */
    @PostMapping("/ops")
    @PreAuthorize("@orgSecurity.canWritePieces(#orgSlug, principal)")
    public ResponseEntity<SceneEditResponseDto> applyOps(
            @PathVariable String orgSlug,
            @PathVariable Integer timelineId,
            @RequestBody SceneOpsRequestDto dto
    ) {
        Integer orgId = orgResolver.requireCurrent(orgSlug).getId();
        TimelineSceneService.SceneEdit edit = sceneService.applyOps(orgId, timelineId, dto.ops(), dto.expectedVersion());
        return ResponseEntity.ok(new SceneEditResponseDto(
                TimelineSceneResponseDto.from(edit.scene(), sceneService.documentOf(edit.scene())),
                edit.createdIds(), edit.issues(), edit.summary()));
    }

    @GetMapping("/summary")
    @PreAuthorize("@orgSecurity.canReadOrgContent(#orgSlug, principal)")
    public ResponseEntity<SceneSummary> summary(
            @PathVariable String orgSlug,
            @PathVariable Integer timelineId
    ) {
        Integer orgId = orgResolver.requireCurrent(orgSlug).getId();
        return ResponseEntity.ok(sceneService.summary(orgId, timelineId));
    }

    @PostMapping("/validate")
    @PreAuthorize("@orgSecurity.canReadOrgContent(#orgSlug, principal)")
    public ResponseEntity<SceneValidationDto> validate(
            @PathVariable String orgSlug,
            @PathVariable Integer timelineId
    ) {
        Integer orgId = orgResolver.requireCurrent(orgSlug).getId();
        List<SceneIssue> issues = sceneService.validate(orgId, timelineId);
        return ResponseEntity.ok(SceneValidationDto.of(issues));
    }
}
