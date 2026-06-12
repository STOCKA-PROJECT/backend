package com.stocka.backend.modules.timelines.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.stocka.backend.modules.organizations.service.OrganizationResolver;
import com.stocka.backend.modules.timelines.dto.CreateTimelineDto;
import com.stocka.backend.modules.timelines.dto.TimelineResponseDto;
import com.stocka.backend.modules.timelines.dto.UpdateTimelineDto;
import com.stocka.backend.modules.timelines.entity.Timeline;
import com.stocka.backend.modules.timelines.service.TimelineService;

/**
 * REST endpoints for timelines under an organization.
 *
 * <p>Reads accept any organization member (including SPECTATOR); writes require OWNER, MANAGER or
 * USER — the same access rules as pieces.
 */
@RestController
@RequestMapping("/organizations/{orgSlug}/timelines")
public class TimelineController {
    private final TimelineService timelineService;
    private final OrganizationResolver orgResolver;

    public TimelineController(TimelineService timelineService, OrganizationResolver orgResolver) {
        this.timelineService = timelineService;
        this.orgResolver = orgResolver;
    }

    @PostMapping
    @PreAuthorize("@orgSecurity.canWritePieces(#orgSlug, principal)")
    public ResponseEntity<TimelineResponseDto> create(
            @PathVariable String orgSlug,
            @RequestBody CreateTimelineDto dto
    ) {
        Integer orgId = orgResolver.requireCurrent(orgSlug).getId();
        Timeline timeline = timelineService.create(orgId, dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(TimelineResponseDto.from(timeline));
    }

    @GetMapping
    @PreAuthorize("@orgSecurity.canReadOrgContent(#orgSlug, principal)")
    public ResponseEntity<List<TimelineResponseDto>> list(@PathVariable String orgSlug) {
        Integer orgId = orgResolver.requireCurrent(orgSlug).getId();
        List<TimelineResponseDto> out = timelineService.listAll(orgId).stream()
                .map(TimelineResponseDto::from)
                .toList();
        return ResponseEntity.ok(out);
    }

    @GetMapping("/{timelineId}")
    @PreAuthorize("@orgSecurity.canReadOrgContent(#orgSlug, principal)")
    public ResponseEntity<TimelineResponseDto> getOne(
            @PathVariable String orgSlug,
            @PathVariable Integer timelineId
    ) {
        Integer orgId = orgResolver.requireCurrent(orgSlug).getId();
        return ResponseEntity.ok(TimelineResponseDto.from(timelineService.findInOrg(orgId, timelineId)));
    }

    @PatchMapping("/{timelineId}")
    @PreAuthorize("@orgSecurity.canWritePieces(#orgSlug, principal)")
    public ResponseEntity<TimelineResponseDto> update(
            @PathVariable String orgSlug,
            @PathVariable Integer timelineId,
            @RequestBody UpdateTimelineDto dto
    ) {
        Integer orgId = orgResolver.requireCurrent(orgSlug).getId();
        return ResponseEntity.ok(TimelineResponseDto.from(timelineService.update(orgId, timelineId, dto)));
    }

    @DeleteMapping("/{timelineId}")
    @PreAuthorize("@orgSecurity.canWritePieces(#orgSlug, principal)")
    public ResponseEntity<Void> delete(
            @PathVariable String orgSlug,
            @PathVariable Integer timelineId
    ) {
        Integer orgId = orgResolver.requireCurrent(orgSlug).getId();
        timelineService.softDelete(orgId, timelineId);
        return ResponseEntity.noContent().build();
    }
}
