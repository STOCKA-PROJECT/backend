package com.stocka.backend.modules.timelines.service;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.stocka.backend.modules.common.error.ApiException;
import com.stocka.backend.modules.common.error.ErrorCodes;
import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.pieces.repository.PieceRepository;
import com.stocka.backend.modules.piecetypes.repository.PieceTypeActionRepository;
import com.stocka.backend.modules.timelines.dto.UpsertTimelineSceneDto;
import com.stocka.backend.modules.timelines.entity.Timeline;
import com.stocka.backend.modules.timelines.entity.TimelineScene;
import com.stocka.backend.modules.timelines.repository.TimelineSceneRepository;

import tools.jackson.databind.JsonNode;

/**
 * Loads and persists the editor {@link TimelineScene} document for a timeline. The document is an
 * opaque, front-end-owned JSON tree; this service only validates structural integrity, size, and
 * that referenced pieces / piece-type actions are not foreign to the organization, and enforces
 * optimistic concurrency for autosave.
 */
@Service
public class TimelineSceneService {
    /** Hard cap on the serialized document, defends against accidental/abusive payloads. */
    static final int MAX_DOCUMENT_BYTES = 2_000_000;

    private final TimelineSceneRepository sceneRepository;
    private final TimelineService timelineService;
    private final TimelineSceneJsonCodec codec;
    private final PieceRepository pieceRepository;
    private final PieceTypeActionRepository actionRepository;

    public TimelineSceneService(
            TimelineSceneRepository sceneRepository,
            TimelineService timelineService,
            TimelineSceneJsonCodec codec,
            PieceRepository pieceRepository,
            PieceTypeActionRepository actionRepository
    ) {
        this.sceneRepository = sceneRepository;
        this.timelineService = timelineService;
        this.codec = codec;
        this.pieceRepository = pieceRepository;
        this.actionRepository = actionRepository;
    }

    /**
     * Returns the scene of a timeline, or empty when it has never been saved.
     *
     * @param orgId      organization id
     * @param timelineId timeline id
     * @return the scene, or {@link Optional#empty()}
     */
    public Optional<TimelineScene> find(Integer orgId, Integer timelineId) {
        Timeline timeline = timelineService.findInOrg(orgId, timelineId);
        return sceneRepository.findByTimeline(timeline);
    }

    /** Parses the stored document of a scene into its JSON tree (may be {@code null}). */
    public JsonNode documentOf(TimelineScene scene) {
        return codec.deserialize(scene.getDocument());
    }

    /**
     * Creates or updates the scene document of a timeline with optimistic concurrency.
     *
     * @param orgId      organization id
     * @param timelineId timeline id
     * @param dto        new document + expected {@code version} (null on first save)
     * @return the persisted scene
     * @throws ApiException 422 on a stale version, 400 on an invalid/oversized document or a
     *                      cross-organization reference
     */
    @Transactional
    public TimelineScene upsert(Integer orgId, Integer timelineId, UpsertTimelineSceneDto dto) {
        Timeline timeline = timelineService.findInOrg(orgId, timelineId);
        Organization org = timeline.getOrganization();

        JsonNode document = dto.getDocument();
        if (document == null || document.isNull()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.TIMELINE_SCENE_DOCUMENT_INVALID);
        }
        String serialized = codec.serialize(document);
        if (serialized != null && serialized.length() > MAX_DOCUMENT_BYTES) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.TIMELINE_SCENE_DOCUMENT_INVALID);
        }
        validateReferences(org, document);

        TimelineScene scene = sceneRepository.findByTimeline(timeline).orElse(null);
        if (scene == null) {
            scene = new TimelineScene()
                    .setTimeline(timeline)
                    .setVersion(1);
        } else {
            // Optimistic concurrency: the client must have loaded the current revision.
            if (dto.getVersion() != null && dto.getVersion() != scene.getVersion()) {
                throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.TIMELINE_SCENE_VERSION_CONFLICT);
            }
            scene.setVersion(scene.getVersion() + 1);
        }
        scene.setSchemaVersion(dto.getSchemaVersion() == null ? 1 : dto.getSchemaVersion());
        scene.setDocument(serialized);
        return sceneRepository.save(scene);
    }

    /**
     * Light integrity check: internal references (item→layer, track→item, clip→track) must resolve,
     * and any referenced piece / piece-type action that resolves must belong to {@code org} (guards
     * against cross-organization references). Stale references (ids that no longer resolve, e.g. a
     * deleted piece) are tolerated so autosave keeps working.
     */
    private void validateReferences(Organization org, JsonNode document) {
        Set<String> layerIds = collectStringIds(document.path("layers"), "id");
        Set<String> itemIds = collectStringIds(document.path("items"), "id");
        Set<String> trackIds = collectStringIds(document.path("tracks"), "id");

        Set<Integer> pieceIds = new HashSet<>();
        for (JsonNode item : asArray(document.path("items"))) {
            requireRef(layerIds, text(item, "layerId"));
            Integer pieceId = intOrNull(item, "pieceId");
            if (pieceId != null) {
                pieceIds.add(pieceId);
            }
        }
        for (JsonNode track : asArray(document.path("tracks"))) {
            requireRef(itemIds, text(track, "itemId"));
        }
        Set<Integer> actionIds = new HashSet<>();
        for (JsonNode clip : asArray(document.path("clips"))) {
            requireRef(trackIds, text(clip, "trackId"));
            Integer actionId = intOrNull(clip, "pieceTypeActionId");
            if (actionId != null) {
                actionIds.add(actionId);
            }
        }

        // Reject only references that resolve to a DIFFERENT organization.
        pieceRepository.findAllById(pieceIds).forEach(p -> {
            if (!p.getOrganization().getId().equals(org.getId())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.TIMELINE_SCENE_INVALID_REFERENCE);
            }
        });
        actionRepository.findAllById(actionIds).forEach(a -> {
            if (!a.getPieceType().getOrganization().getId().equals(org.getId())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.TIMELINE_SCENE_INVALID_REFERENCE);
            }
        });
    }

    private void requireRef(Set<String> known, String ref) {
        if (ref != null && !known.contains(ref)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.TIMELINE_SCENE_INVALID_REFERENCE);
        }
    }

    private Set<String> collectStringIds(JsonNode array, String field) {
        Set<String> ids = new HashSet<>();
        for (JsonNode node : asArray(array)) {
            String id = text(node, field);
            if (id != null) {
                ids.add(id);
            }
        }
        return ids;
    }

    private static Iterable<JsonNode> asArray(JsonNode node) {
        return node != null && node.isArray() ? node : Set.of();
    }

    private static String text(JsonNode node, String field) {
        return node.path(field).asText(null);
    }

    private static Integer intOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.intValue() : null;
    }
}
