package com.stocka.backend.modules.timelines.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.stocka.backend.modules.common.error.ApiException;
import com.stocka.backend.modules.common.error.ErrorCodes;
import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.organizations.service.OrganizationService;
import com.stocka.backend.modules.timelines.dto.CreateTimelineDto;
import com.stocka.backend.modules.timelines.dto.UpdateTimelineDto;
import com.stocka.backend.modules.timelines.entity.Timeline;
import com.stocka.backend.modules.timelines.repository.TimelineRepository;
import com.stocka.backend.modules.timelines.repository.TimelineSceneRepository;

/**
 * CRUD for {@link Timeline} entities. Names are unique per organization; deletes are soft and free
 * the name for reuse by mangling the stored value, mirroring the piece-types pattern.
 */
@Service
public class TimelineService {
    static final int MAX_NAME_LENGTH = 120;
    private static final String SOFT_DELETE_SUFFIX = "::deleted::";

    private final TimelineRepository timelineRepository;
    private final OrganizationService organizationService;
    private final TimelineSceneRepository sceneRepository;

    public TimelineService(
            TimelineRepository timelineRepository,
            OrganizationService organizationService,
            TimelineSceneRepository sceneRepository
    ) {
        this.timelineRepository = timelineRepository;
        this.organizationService = organizationService;
        this.sceneRepository = sceneRepository;
    }

    @Transactional
    public Timeline create(Integer orgId, CreateTimelineDto dto) {
        Organization org = organizationService.findById(orgId);
        String name = sanitizeName(dto.getName());
        ensureUniqueName(org, name, null);

        Timeline timeline = new Timeline()
                .setOrganization(org)
                .setName(name);
        return saveAndFlush(timeline);
    }

    public List<Timeline> listAll(Integer orgId) {
        Organization org = organizationService.findById(orgId);
        return timelineRepository.findByOrganization(org);
    }

    public Timeline findInOrg(Integer orgId, Integer timelineId) {
        Organization org = organizationService.findById(orgId);
        Timeline timeline = timelineRepository.findById(timelineId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.TIMELINES_NOT_FOUND));
        if (!timeline.getOrganization().getId().equals(org.getId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.TIMELINES_NOT_FOUND);
        }
        return timeline;
    }

    @Transactional
    public Timeline update(Integer orgId, Integer timelineId, UpdateTimelineDto dto) {
        Timeline timeline = findInOrg(orgId, timelineId);
        if (dto.getName() != null) {
            String newName = sanitizeName(dto.getName());
            if (!newName.equals(timeline.getName())) {
                ensureUniqueName(timeline.getOrganization(), newName, timeline.getId());
                timeline.setName(newName);
            }
        }
        return saveAndFlush(timeline);
    }

    @Transactional
    public void softDelete(Integer orgId, Integer timelineId) {
        Timeline timeline = findInOrg(orgId, timelineId);
        // Free up the name so a new active timeline with the same name can be created. The DB-level
        // uk_timeline_org_name UNIQUE constraint covers (organization_id, name) regardless of
        // deleted_at; appending an id-based suffix guarantees uniqueness across soft-deleted rows
        // without breaking same-org enforcement on active rows.
        timeline.setName(buildSoftDeletedName(timeline.getName(), timeline.getId(), MAX_NAME_LENGTH));
        timeline.setDeletedAt(LocalDateTime.now());
        timelineRepository.save(timeline);
        // Cascade: free the timeline's editor scene so it does not dangle.
        sceneRepository.softDeleteByTimeline(timeline);
    }

    private void ensureUniqueName(Organization org, String name, Integer excludeId) {
        Optional<Timeline> existing = timelineRepository.findByOrganizationAndName(org, name);
        if (existing.isPresent() && (excludeId == null || !existing.get().getId().equals(excludeId))) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.TIMELINES_NAME_CONFLICT);
        }
    }

    private Timeline saveAndFlush(Timeline timeline) {
        try {
            return timelineRepository.saveAndFlush(timeline);
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.TIMELINES_NAME_CONFLICT, null, ex);
        }
    }

    private String sanitizeName(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El nombre es obligatorio");
        }
        String trimmed = raw.trim();
        if (trimmed.length() > MAX_NAME_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El nombre no puede superar " + MAX_NAME_LENGTH + " caracteres");
        }
        return trimmed;
    }

    /**
     * Builds the renamed value for a soft-deleted row so its original name is freed for a new
     * active row with the same name. The id-based suffix guarantees the renamed value stays unique
     * across all previously soft-deleted rows.
     *
     * @param currentName the live name about to be released
     * @param id          primary key of the row being soft-deleted
     * @param maxLength   maximum length allowed by the column
     * @return the value to store in {@code name} alongside {@code deleted_at}
     */
    static String buildSoftDeletedName(String currentName, Integer id, int maxLength) {
        String suffix = SOFT_DELETE_SUFFIX + (id == null ? "?" : id);
        int maxBaseLen = Math.max(0, maxLength - suffix.length());
        String base = currentName == null ? "" : currentName;
        if (base.length() > maxBaseLen) {
            base = base.substring(0, maxBaseLen);
        }
        return base + suffix;
    }
}
