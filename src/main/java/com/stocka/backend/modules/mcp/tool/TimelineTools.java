package com.stocka.backend.modules.mcp.tool;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.stocka.backend.modules.common.schema.Param;
import com.stocka.backend.modules.mcp.tool.support.OrgAccess;
import com.stocka.backend.modules.oauth.OAuthScope;
import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.timelines.dto.CreateTimelineDto;
import com.stocka.backend.modules.timelines.dto.DuplicateTimelineDto;
import com.stocka.backend.modules.timelines.dto.UpdateTimelineDto;
import com.stocka.backend.modules.timelines.dto.UpsertTimelineSceneDto;
import com.stocka.backend.modules.timelines.entity.Timeline;
import com.stocka.backend.modules.timelines.entity.TimelineScene;
import com.stocka.backend.modules.timelines.scene.SceneDocument;
import com.stocka.backend.modules.timelines.scene.SceneIssue;
import com.stocka.backend.modules.timelines.scene.SceneOp;
import com.stocka.backend.modules.timelines.scene.SceneSummary;
import com.stocka.backend.modules.timelines.service.TimelineSceneService;
import com.stocka.backend.modules.timelines.service.TimelineService;
import com.stocka.backend.modules.timelines.show.ShowPackage;
import com.stocka.backend.modules.timelines.show.ShowService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Timelines and their scenes: list, inspect (compact or raw), create, duplicate, edit through
 * operations, validate, export as a show package, replace, delete.
 */
@Component
public class TimelineTools implements ToolSource {

    public record OrgInput(@Param(value = "Organization slug", required = true) String org) {
    }

    public record TimelineInput(
            @Param(value = "Organization slug", required = true) String org,
            @Param(value = "Timeline id", required = true) Integer timelineId) {
    }

    public record TimelineView(
            @Param("Timeline id") Integer id,
            @Param("Name") String name,
            @Param("Scene version to pass as expectedVersion; null when the scene was never saved") Integer version,
            @Param("Show length in ms: the end of its last clip") Long durationMs,
            @Param("Items on the board") Integer items,
            @Param("Clips") Integer clips,
            @Param("Created") Date createdAt,
            @Param("Last update") Date updatedAt) {
    }

    public record TimelinesResult(@Param("Timelines") List<TimelineView> items) {
    }

    public record TimelineDetail(
            @Param("Timeline") TimelineView timeline,
            @Param("Compact view of the scene with validation findings") SceneSummary scene) {
    }

    public record CreateInput(
            @Param(value = "Organization slug", required = true) String org,
            @Param(value = "Name, unique in the organization", required = true) String name,
            @Param("Length in ms of the empty timeline (default 60000); once it has clips the show lasts until the end of the last one") Long durationMs,
            @Param("Board width in cm (default 128)") Double boardWidthCm,
            @Param("Board height in cm (default 72)") Double boardHeightCm) {
    }

    public record UpdateInput(
            @Param(value = "Organization slug", required = true) String org,
            @Param(value = "Timeline id", required = true) Integer timelineId,
            @Param(value = "New name", required = true) String name) {
    }

    public record DuplicateInput(
            @Param(value = "Organization slug", required = true) String org,
            @Param(value = "Timeline id to copy", required = true) Integer timelineId,
            @Param("Name of the copy, unique in the organization (default: \"<name> (copy)\", numbered when taken)") String name) {
    }

    public record SceneGetInput(
            @Param(value = "Organization slug", required = true) String org,
            @Param(value = "Timeline id", required = true) Integer timelineId,
            @Param("compact (default: summary with names, cm and issues) or raw (the full editor document)") String format) {
    }

    public record EditInput(
            @Param(value = "Organization slug", required = true) String org,
            @Param(value = "Timeline id", required = true) Integer timelineId,
            @Param(value = "Operations, applied in order and atomically (max 100)", required = true) List<SceneOp> ops,
            @Param("Scene version you read (timelines_list / timeline_get / previous edit). Required once the scene exists") Integer expectedVersion) {
    }

    public record EditResult(
            @Param("New scene version; pass it to the next edit") int version,
            @Param("Ids created by the batch, in order (layers, items, tracks, clips)") List<String> createdIds,
            @Param("One line per applied operation") List<String> log,
            @Param("Number of validation errors after the edit") int errors,
            @Param("Validation findings after the edit") List<SceneIssue> issues,
            @Param("Compact view of the scene after the edit") SceneSummary scene) {
    }

    public record PutInput(
            @Param(value = "Organization slug", required = true) String org,
            @Param(value = "Timeline id", required = true) Integer timelineId,
            @Param(value = "Full scene document (see the stocka://schema/scene resource)", required = true) Map<String, Object> document,
            @Param("Scene version you read; required once the scene exists") Integer expectedVersion) {
    }

    public record ValidationResult(
            @Param("Whether the scene has no errors") boolean valid,
            @Param("Number of errors") int errors,
            @Param("Number of warnings") int warnings,
            @Param("Findings, errors first") List<SceneIssue> issues) {
    }

    private final OrgAccess access;
    private final TimelineService timelines;
    private final TimelineSceneService scenes;
    private final ShowService shows;
    private final JsonMapper json;

    public TimelineTools(OrgAccess access, TimelineService timelines, TimelineSceneService scenes, ShowService shows,
                         JsonMapper json) {
        this.access = access;
        this.timelines = timelines;
        this.scenes = scenes;
        this.shows = shows;
        this.json = json;
    }

    @Override
    public List<ToolDefinition<?>> tools() {
        return List.of(
                ToolDefinition.of("timelines_list",
                        "Timelines of the organization with their scene version, length and counts.",
                        OrgInput.class, OAuthScope.READ, ToolHints.READ, this::list).returning(TimelinesResult.class),
                ToolDefinition.of("timeline_get",
                        "A timeline with the compact view of its scene: items (piece names, ports, position in cm), "
                                + "tracks, clips in time order with action names and parameters, and validation findings.",
                        TimelineInput.class, OAuthScope.READ, ToolHints.READ, this::get).returning(TimelineDetail.class),
                ToolDefinition.of("timeline_scene_get",
                        "The scene of a timeline: format compact (default) returns the summary; raw returns the full "
                                + "editor document (large). Both include the version.",
                        SceneGetInput.class, OAuthScope.READ, ToolHints.READ, this::sceneGet),
                ToolDefinition.of("timeline_create",
                        "Creates a timeline with an empty scene (one layer, given length and board size) and returns "
                                + "its id and scene version. Needs OWNER, MANAGER or USER.",
                        CreateInput.class, OAuthScope.TIMELINES_WRITE, ToolHints.CREATE, this::create)
                        .returning(TimelineView.class),
                ToolDefinition.of("timeline_duplicate",
                        "Copies a timeline with its whole scene (board, items, tracks, clips, schedule) into a new "
                                + "timeline and returns it; the copy's scene starts at version 1. Needs OWNER, MANAGER or USER.",
                        DuplicateInput.class, OAuthScope.TIMELINES_WRITE, ToolHints.CREATE, this::duplicate)
                        .returning(TimelineView.class),
                ToolDefinition.of("timeline_update",
                        "Renames a timeline. Needs OWNER, MANAGER or USER.",
                        UpdateInput.class, OAuthScope.TIMELINES_WRITE, ToolHints.UPDATE, this::update)
                        .returning(TimelineView.class),
                ToolDefinition.of("timeline_scene_edit",
                        "Edits the scene with a batch of operations (add_item, add_clip, move_clip, remove_clip, "
                                + "set_board …), applied atomically. The show lasts until the end of its last clip. Sizes in cm, times in ms, references by id or "
                                + "unique name. Returns the new version, the ids created (use them in the next batch), "
                                + "validation findings and the compact scene. On timeline_scenes.version_conflict "
                                + "re-read the scene and retry. Needs OWNER, MANAGER or USER.",
                        EditInput.class, OAuthScope.TIMELINES_WRITE, ToolHints.UPDATE, this::edit)
                        .returning(EditResult.class),
                ToolDefinition.of("timeline_scene_put",
                        "Replaces the whole scene document (for restores or bulk rewrites; prefer timeline_scene_edit). "
                                + "Needs OWNER, MANAGER or USER.",
                        PutInput.class, OAuthScope.TIMELINES_WRITE, ToolHints.UPDATE, this::put)
                        .returning(TimelineView.class),
                ToolDefinition.of("timeline_validate",
                        "Validates the scene (references, clip parameters against the action definitions and the "
                                + "output catalog, timing) without changing anything.",
                        TimelineInput.class, OAuthScope.READ, ToolHints.READ, this::validate)
                        .returning(ValidationResult.class),
                ToolDefinition.of("timeline_show_export",
                        "Exports the scene as the stocka-show package the Raspberry Pi plays (hardware from the "
                                + "organization's ports, events with parameters coerced to the catalog types, assets, "
                                + "meta.hash). Fails with timeline_shows.invalid and the blocking issues when the scene "
                                + "has errors; fix them with timeline_scene_edit. The same package is served at "
                                + "GET /organizations/{org}/timelines/{id}/show and bundled with its media at /bundle.",
                        TimelineInput.class, OAuthScope.READ, ToolHints.READ, this::showExport)
                        .returning(ShowPackage.class),
                ToolDefinition.of("timeline_delete",
                        "Deletes a timeline and its scene. Needs OWNER, MANAGER or USER and the delete scope.",
                        TimelineInput.class, OAuthScope.DELETE, ToolHints.DELETE, this::delete)
                        .returning(ToolResults.Done.class));
    }

    private TimelinesResult list(ToolCall<OrgInput> call) {
        Organization org = access.read(call.caller(), call.input().org());
        List<TimelineView> out = new ArrayList<>();
        for (Timeline timeline : timelines.listAll(org.getId())) {
            out.add(view(org, timeline));
        }
        return new TimelinesResult(out);
    }

    private TimelineDetail get(ToolCall<TimelineInput> call) {
        Organization org = access.read(call.caller(), call.input().org());
        Timeline timeline = timelines.findInOrg(org.getId(), call.input().timelineId());
        return new TimelineDetail(view(org, timeline), scenes.summary(org.getId(), timeline.getId()));
    }

    private Object sceneGet(ToolCall<SceneGetInput> call) {
        Organization org = access.read(call.caller(), call.input().org());
        Timeline timeline = timelines.findInOrg(org.getId(), call.input().timelineId());
        Optional<TimelineScene> scene = scenes.find(org.getId(), timeline.getId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("timelineId", timeline.getId());
        out.put("version", scene.map(TimelineScene::getVersion).orElse(null));
        if ("raw".equalsIgnoreCase(call.input().format())) {
            JsonNode document = scene.map(scenes::documentOf).orElse(null);
            out.put("document", document == null ? null : json.convertValue(document, Object.class));
        } else {
            out.put("scene", scenes.summary(org.getId(), timeline.getId()));
        }
        return out;
    }

    private TimelineView create(ToolCall<CreateInput> call) {
        CreateInput in = call.input();
        Organization org = access.writePieces(call.caller(), in.org());
        CreateTimelineDto dto = new CreateTimelineDto();
        dto.setName(in.name());
        Timeline timeline = timelines.create(org.getId(), dto);
        double width = in.boardWidthCm() == null || in.boardWidthCm() <= 0 ? 128 : in.boardWidthCm();
        double height = in.boardHeightCm() == null || in.boardHeightCm() <= 0 ? 72 : in.boardHeightCm();
        long duration = in.durationMs() == null || in.durationMs() <= 0 ? SceneDocument.DEFAULT_DURATION_MS : in.durationMs();
        scenes.createEmpty(org.getId(), timeline.getId(), duration,
                new SceneDocument.Board(SceneDocument.toPx(width), SceneDocument.toPx(height), null));
        return view(org, timeline);
    }

    private TimelineView duplicate(ToolCall<DuplicateInput> call) {
        DuplicateInput in = call.input();
        Organization org = access.writePieces(call.caller(), in.org());
        DuplicateTimelineDto dto = new DuplicateTimelineDto();
        dto.setName(in.name());
        return view(org, timelines.duplicate(org.getId(), in.timelineId(), dto));
    }

    private TimelineView update(ToolCall<UpdateInput> call) {
        Organization org = access.writePieces(call.caller(), call.input().org());
        UpdateTimelineDto dto = new UpdateTimelineDto();
        dto.setName(call.input().name());
        return view(org, timelines.update(org.getId(), call.input().timelineId(), dto));
    }

    private EditResult edit(ToolCall<EditInput> call) {
        EditInput in = call.input();
        Organization org = access.writePieces(call.caller(), in.org());
        TimelineSceneService.SceneEdit edit = scenes.applyOps(org.getId(), in.timelineId(), in.ops(), in.expectedVersion());
        int errors = (int) edit.issues().stream().filter(SceneIssue::isError).count();
        return new EditResult(edit.scene().getVersion(), edit.createdIds(), List.of(), errors, edit.issues(), edit.summary());
    }

    private TimelineView put(ToolCall<PutInput> call) {
        PutInput in = call.input();
        Organization org = access.writePieces(call.caller(), in.org());
        UpsertTimelineSceneDto dto = new UpsertTimelineSceneDto();
        dto.setDocument(json.valueToTree(in.document()));
        dto.setVersion(in.expectedVersion());
        dto.setSchemaVersion(SceneDocument.SCHEMA_VERSION);
        scenes.upsert(org.getId(), in.timelineId(), dto);
        return view(org, timelines.findInOrg(org.getId(), in.timelineId()));
    }

    private ValidationResult validate(ToolCall<TimelineInput> call) {
        Organization org = access.read(call.caller(), call.input().org());
        List<SceneIssue> issues = scenes.validate(org.getId(), call.input().timelineId());
        int errors = (int) issues.stream().filter(SceneIssue::isError).count();
        return new ValidationResult(errors == 0, errors, issues.size() - errors, issues);
    }

    private ShowPackage showExport(ToolCall<TimelineInput> call) {
        Organization org = access.read(call.caller(), call.input().org());
        return shows.showPackage(org.getId(), call.input().timelineId());
    }

    private ToolResults.Done delete(ToolCall<TimelineInput> call) {
        Organization org = access.writePieces(call.caller(), call.input().org());
        timelines.softDelete(org.getId(), call.input().timelineId());
        return ToolResults.Done.of("Timeline " + call.input().timelineId() + " deleted");
    }

    private TimelineView view(Organization org, Timeline timeline) {
        Optional<TimelineScene> scene = scenes.find(org.getId(), timeline.getId());
        Integer version = null;
        Long duration = null;
        Integer items = null;
        Integer clips = null;
        if (scene.isPresent()) {
            version = scene.get().getVersion();
            JsonNode document = scenes.documentOf(scene.get());
            if (document != null) {
                duration = durationOf(document);
                items = document.path("items").isArray() ? document.path("items").size() : 0;
                clips = document.path("clips").isArray() ? document.path("clips").size() : 0;
            }
        }
        return new TimelineView(timeline.getId(), timeline.getName(), version, duration, items, clips,
                timeline.getCreatedAt(), timeline.getUpdatedAt());
    }

    /** The end of the last clip, as {@link SceneDocument} derives it; the stored value without clips. */
    private static Long durationOf(JsonNode document) {
        long end = -1;
        for (JsonNode clip : document.path("clips")) {
            end = Math.max(end, clip.path("startMs").asLong(0) + clip.path("durationMs").asLong(0));
        }
        if (end >= 0) {
            return end;
        }
        return document.path("durationMs").isNumber() ? document.path("durationMs").longValue() : null;
    }
}
