package com.stocka.backend.modules.timelines.service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.stocka.backend.modules.common.error.ApiException;
import com.stocka.backend.modules.common.error.ErrorCodes;
import com.stocka.backend.modules.contract.OutputCatalog;
import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.pieces.repository.PieceRepository;
import com.stocka.backend.modules.piecetypes.repository.PieceTypeActionRepository;
import com.stocka.backend.modules.piecetypes.service.PieceTypeActionService;
import com.stocka.backend.modules.piecetypes.service.PieceTypeService;
import com.stocka.backend.modules.ports.repository.PortRepository;
import com.stocka.backend.modules.timelines.dto.UpsertTimelineSceneDto;
import com.stocka.backend.modules.timelines.entity.Timeline;
import com.stocka.backend.modules.timelines.entity.TimelineScene;
import com.stocka.backend.modules.timelines.repository.TimelineSceneRepository;
import com.stocka.backend.modules.timelines.scene.JpaSceneContext;
import com.stocka.backend.modules.timelines.scene.SceneContext;
import com.stocka.backend.modules.timelines.scene.SceneDocument;
import com.stocka.backend.modules.timelines.scene.SceneEditor;
import com.stocka.backend.modules.timelines.scene.SceneIssue;
import com.stocka.backend.modules.timelines.scene.SceneJsonCodec;
import com.stocka.backend.modules.timelines.scene.SceneOp;
import com.stocka.backend.modules.timelines.scene.SceneOpException;
import com.stocka.backend.modules.timelines.scene.SceneSummary;
import com.stocka.backend.modules.timelines.scene.SceneValidator;

import tools.jackson.databind.JsonNode;

/**
 * Loads and persists the editor {@link TimelineScene} document for a timeline.
 *
 * <p>Two ways in: the editor saves the whole document ({@link #upsert}), the server only checking
 * structural integrity, size and cross-organization references; servers-side callers (the MCP tools,
 * the REST operations endpoint) edit through {@link SceneOp}s ({@link #applyOps}), which are applied,
 * validated and saved atomically. Both paths use the {@code version} for optimistic concurrency.
 */
@Service
public class TimelineSceneService {

    /** Hard cap on the serialized document, defends against accidental/abusive payloads. */
    static final int MAX_DOCUMENT_BYTES = 2_000_000;
    /** Most operations accepted in one batch. */
    public static final int MAX_OPS_PER_BATCH = 100;

    /** Outcome of {@link #applyOps}. */
    public record SceneEdit(TimelineScene scene, SceneDocument document, List<String> createdIds,
                            List<SceneIssue> issues, SceneSummary summary) {
    }

    private final TimelineSceneRepository sceneRepository;
    private final TimelineService timelineService;
    private final TimelineSceneJsonCodec codec;
    private final SceneJsonCodec sceneCodec;
    private final PieceRepository pieceRepository;
    private final PieceTypeActionRepository actionRepository;
    private final PortRepository portRepository;
    private final PieceTypeService pieceTypeService;
    private final PieceTypeActionService actionService;
    private final OutputCatalog catalog;

    public TimelineSceneService(
            TimelineSceneRepository sceneRepository,
            TimelineService timelineService,
            TimelineSceneJsonCodec codec,
            SceneJsonCodec sceneCodec,
            PieceRepository pieceRepository,
            PieceTypeActionRepository actionRepository,
            PortRepository portRepository,
            PieceTypeService pieceTypeService,
            PieceTypeActionService actionService,
            OutputCatalog catalog
    ) {
        this.sceneRepository = sceneRepository;
        this.timelineService = timelineService;
        this.codec = codec;
        this.sceneCodec = sceneCodec;
        this.pieceRepository = pieceRepository;
        this.actionRepository = actionRepository;
        this.portRepository = portRepository;
        this.pieceTypeService = pieceTypeService;
        this.actionService = actionService;
        this.catalog = catalog;
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
     * @throws ApiException 409 on a stale version, 400 on an invalid/oversized document or a
     *                      cross-organization reference
     */
    @Transactional
    public TimelineScene upsert(Integer orgId, Integer timelineId, UpsertTimelineSceneDto dto) {
        Timeline timeline = timelineService.findInOrg(orgId, timelineId);
        JsonNode document = dto.getDocument();
        if (document == null || document.isNull()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.TIMELINE_SCENE_DOCUMENT_INVALID);
        }
        return persist(timeline, document, dto.getVersion(), dto.getSchemaVersion() == null ? 1 : dto.getSchemaVersion());
    }

    /**
     * Applies a batch of operations to the scene (creating it when the timeline has none), validates
     * the result and saves it.
     *
     * @param orgId           organization id
     * @param timelineId      timeline id
     * @param ops             operations in order (1..{@value #MAX_OPS_PER_BATCH})
     * @param expectedVersion version the caller read; required when a scene exists
     * @return the saved scene, the ids created, the validation findings and a summary
     * @throws SceneOpException on the first invalid operation (nothing is saved)
     * @throws ApiException     409 on a stale version, 400 when the version is missing
     */
    @Transactional
    public SceneEdit applyOps(Integer orgId, Integer timelineId, List<SceneOp> ops, Integer expectedVersion) {
        if (ops == null || ops.isEmpty()) {
            throw new SceneOpException(0, "-", "the batch has no operations");
        }
        if (ops.size() > MAX_OPS_PER_BATCH) {
            throw new SceneOpException(MAX_OPS_PER_BATCH, "-", "at most " + MAX_OPS_PER_BATCH + " operations per batch");
        }
        Timeline timeline = timelineService.findInOrg(orgId, timelineId);
        Organization org = timeline.getOrganization();
        TimelineScene existing = sceneRepository.findByTimeline(timeline).orElse(null);
        if (existing != null && expectedVersion == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.TIMELINE_SCENE_VERSION_REQUIRED);
        }
        JsonNode original = existing == null ? null : codec.deserialize(existing.getDocument());
        SceneDocument document = sceneCodec.parse(original);
        SceneContext context = context(org);
        SceneEditor.Change change = SceneEditor.apply(document, ops, context);
        JsonNode tree = sceneCodec.toJson(change.document(), original);
        TimelineScene saved = persist(timeline, tree, expectedVersion, SceneDocument.SCHEMA_VERSION);
        List<SceneIssue> issues = SceneValidator.validate(change.document(), context, catalog);
        return new SceneEdit(saved, change.document(), change.createdIds(), issues,
                SceneSummary.of(change.document(), context, issues));
    }

    /**
     * Creates the empty scene of a timeline that has none.
     *
     * @return the existing scene when there is one, else the new empty scene
     */
    @Transactional
    public TimelineScene createEmpty(Integer orgId, Integer timelineId, long durationMs, SceneDocument.Board board) {
        Timeline timeline = timelineService.findInOrg(orgId, timelineId);
        Optional<TimelineScene> existing = sceneRepository.findByTimeline(timeline);
        if (existing.isPresent()) {
            return existing.get();
        }
        SceneDocument document = SceneDocument.empty(durationMs, board);
        return persist(timeline, sceneCodec.toJson(document, null), null, SceneDocument.SCHEMA_VERSION);
    }

    /** @return the compact summary of the scene (an empty scene when none is saved) */
    public SceneSummary summary(Integer orgId, Integer timelineId) {
        Timeline timeline = timelineService.findInOrg(orgId, timelineId);
        SceneDocument document = load(timeline);
        SceneContext context = context(timeline.getOrganization());
        List<SceneIssue> issues = SceneValidator.validate(document, context, catalog);
        return SceneSummary.of(document, context, issues);
    }

    /** @return the validation findings of the scene */
    public List<SceneIssue> validate(Integer orgId, Integer timelineId) {
        Timeline timeline = timelineService.findInOrg(orgId, timelineId);
        SceneDocument document = load(timeline);
        return SceneValidator.validate(document, context(timeline.getOrganization()), catalog);
    }

    /** @return the typed document of the timeline's scene (empty document when none is saved) */
    public SceneDocument load(Timeline timeline) {
        TimelineScene scene = sceneRepository.findByTimeline(timeline).orElse(null);
        return sceneCodec.parse(scene == null ? null : codec.deserialize(scene.getDocument()));
    }

    private SceneContext context(Organization org) {
        return new JpaSceneContext(org, pieceRepository, portRepository, actionRepository, pieceTypeService, actionService);
    }

    private TimelineScene persist(Timeline timeline, JsonNode document, Integer expectedVersion, int schemaVersion) {
        Organization org = timeline.getOrganization();
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
            if (expectedVersion != null && expectedVersion != scene.getVersion()) {
                throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.TIMELINE_SCENE_VERSION_CONFLICT,
                        Map.of("currentVersion", scene.getVersion()));
            }
            scene.setVersion(scene.getVersion() + 1);
        }
        scene.setSchemaVersion(schemaVersion);
        scene.setDocument(serialized);
        return sceneRepository.save(scene);
    }

    /**
     * Light integrity check: internal references (item→layer, track→item, clip→track) must resolve,
     * and any referenced piece / piece-type action / port that resolves must belong to {@code org}
     * (guards against cross-organization references). An item may carry zero, one or many port ids
     * in its {@code ports} array. Stale references (ids that no longer resolve, e.g. a deleted piece
     * or port) are tolerated so autosave keeps working.
     */
    private void validateReferences(Organization org, JsonNode document) {
        Set<String> layerIds = collectStringIds(document.path("layers"), "id");
        Set<String> itemIds = collectStringIds(document.path("items"), "id");
        Set<String> trackIds = collectStringIds(document.path("tracks"), "id");
        Set<Integer> pieceIds = new HashSet<>();
        Set<Integer> portIds = new HashSet<>();
        for (JsonNode item : asArray(document.path("items"))) {
            requireRef(layerIds, text(item, "layerId"));
            Integer pieceId = intOrNull(item, "pieceId");
            if (pieceId != null) {
                pieceIds.add(pieceId);
            }
            collectInts(item.path("ports"), portIds);
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
        portRepository.findAllById(portIds).forEach(p -> {
            if (!p.getOrganization().getId().equals(org.getId())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.TIMELINE_SCENE_INVALID_REFERENCE);
            }
        });
    }

    private void requireRef(Set<String> known, String ref) {
        if (ref != null && !known.contains(ref)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.TIMELINE_SCENE_INVALID_REFERENCE);
        }
    }

    /** Adds every numeric element of {@code array} to {@code out} (non-array / non-numeric ignored). */
    private static void collectInts(JsonNode array, Set<Integer> out) {
        for (JsonNode value : asArray(array)) {
            if (value.isNumber()) {
                out.add(value.intValue());
            }
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
