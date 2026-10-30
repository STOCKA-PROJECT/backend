package com.stocka.backend.modules.timelines.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
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
import com.stocka.backend.modules.timelines.dto.DuplicateTimelineDto;
import com.stocka.backend.modules.timelines.dto.UpdateTimelineDto;
import com.stocka.backend.modules.timelines.entity.Timeline;
import com.stocka.backend.modules.timelines.entity.TimelineScene;
import com.stocka.backend.modules.timelines.repository.TimelineRepository;
import com.stocka.backend.modules.timelines.repository.TimelineSceneRepository;

/**
 * CRUD and duplication for {@link Timeline} entities. Names are unique per organization; deletes are
 * soft and free the name for reuse by mangling the stored value, mirroring the piece-types pattern.
 */
@Service
public class TimelineService {
    static final int MAX_NAME_LENGTH = 120;
    /** Most "(còpia N)" suffixes tried before giving up with a name conflict. */
    static final int MAX_COPY_NUMBER = 1000;
    private static final String SOFT_DELETE_SUFFIX = "::deleted::";
    private static final String COPY_NAME_KEY = "timelines.copy_name";
    private static final String COPY_NAME_NUMBERED_KEY = "timelines.copy_name_numbered";

    private final TimelineRepository timelineRepository;
    private final OrganizationService organizationService;
    private final TimelineSceneRepository sceneRepository;
    private final MessageSource messageSource;

    public TimelineService(
            TimelineRepository timelineRepository,
            OrganizationService organizationService,
            TimelineSceneRepository sceneRepository,
            MessageSource messageSource
    ) {
        this.timelineRepository = timelineRepository;
        this.organizationService = organizationService;
        this.sceneRepository = sceneRepository;
        this.messageSource = messageSource;
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

    /**
     * Copies a timeline and its scene into a new timeline of the same organization. The scene
     * document is copied verbatim (its references already belong to the organization) and starts
     * again at version 1; a timeline that was never edited gives a copy without scene.
     *
     * @param orgId      organization id
     * @param timelineId timeline to copy
     * @param dto        optional explicit name; {@code null} (or a {@code null} name) picks the
     *                   first free "&lt;name&gt; (còpia)", "&lt;name&gt; (còpia 2)"… in the request locale
     * @return the new timeline
     * @throws ApiException 404 when the timeline is not in the organization, 409 when the explicit
     *                      name is taken or no copy name is free
     */
    @Transactional
    public Timeline duplicate(Integer orgId, Integer timelineId, DuplicateTimelineDto dto) {
        Timeline source = findInOrg(orgId, timelineId);
        Organization org = source.getOrganization();
        String name;
        if (dto != null && dto.getName() != null) {
            name = sanitizeName(dto.getName());
            ensureUniqueName(org, name, null);
        } else {
            name = freeCopyName(org, source.getName());
        }
        Timeline copy = saveAndFlush(new Timeline()
                .setOrganization(org)
                .setName(name));
        sceneRepository.findByTimeline(source).ifPresent(scene -> sceneRepository.save(new TimelineScene()
                .setTimeline(copy)
                .setSchemaVersion(scene.getSchemaVersion())
                .setDocument(scene.getDocument())
                .setVersion(1)));
        return copy;
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

    private String freeCopyName(Organization org, String sourceName) {
        Set<String> taken = timelineRepository.findByOrganization(org).stream()
                .map(Timeline::getName)
                .collect(Collectors.toSet());
        Locale locale = LocaleContextHolder.getLocale();
        for (int number = 1; number <= MAX_COPY_NUMBER; number++) {
            String candidate = copyName(sourceName, number, locale);
            if (!taken.contains(candidate)) {
                return candidate;
            }
        }
        throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.TIMELINES_NAME_CONFLICT);
    }

    /**
     * Formats the {@code number}-th copy name of {@code base}, cutting the base so the result fits
     * {@link #MAX_NAME_LENGTH}.
     */
    private String copyName(String base, int number, Locale locale) {
        String key = number == 1 ? COPY_NAME_KEY : COPY_NAME_NUMBERED_KEY;
        // The number goes as text so MessageFormat does not group its digits ("1.000").
        Object[] args = {base, String.valueOf(number)};
        String name = messageSource.getMessage(key, args, locale);
        int overflow = name.length() - MAX_NAME_LENGTH;
        if (overflow > 0) {
            args[0] = base.substring(0, Math.max(0, base.length() - overflow)).stripTrailing();
            name = messageSource.getMessage(key, args, locale);
        }
        return name;
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
