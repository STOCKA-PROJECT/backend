package com.stocka.backend.modules.timelines.show;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntFunction;

import com.stocka.backend.modules.piecetypes.dto.ActionParameterDto;
import com.stocka.backend.modules.timelines.scene.SceneContext;
import com.stocka.backend.modules.timelines.scene.SceneContext.ActionRef;
import com.stocka.backend.modules.timelines.scene.SceneContext.PieceRef;
import com.stocka.backend.modules.timelines.scene.SceneDocument;
import com.stocka.backend.modules.timelines.scene.SceneDocument.Clip;
import com.stocka.backend.modules.timelines.scene.SceneDocument.Item;
import com.stocka.backend.modules.timelines.scene.SceneDocument.Track;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The scene resolved for execution: every board item with its assigned ports, and every clip as a
 * step with its action and parameter values materialized (static values parsed from the action
 * definition, dynamic values taken from the clip, the duration parameter from the clip length).
 * {@link ShowPackageBuilder} turns this plan into the package. Port of {@code buildExecutionPlan}
 * in the editor's {@code utils/exportBundle.ts}.
 *
 * @param durationMs show length
 * @param fps        editor frame rate
 * @param items      board items with their ports
 * @param schedule   steps sorted by start time (stable: ties keep document order)
 * @param issues     problems found while resolving
 */
public record ExecutionPlan(
        long durationMs,
        int fps,
        List<ExecutionItem> items,
        List<ExecutionStep> schedule,
        List<ShowIssue> issues) {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * A board item resolved for execution.
     *
     * @param itemId    item id
     * @param pieceId   piece id
     * @param pieceName piece name, or {@code null} when the piece no longer exists
     * @param ports     assigned ports that still exist
     */
    public record ExecutionItem(String itemId, Integer pieceId, String pieceName, List<ShowPort> ports) {
    }

    /**
     * The action of a step.
     *
     * @param id          action id
     * @param name        technical name ({@code turn_on}, {@code animate}...)
     * @param displayName label
     * @param pieceTypeId piece type that owns the action; selects the item's target ports
     */
    public record ExecutionAction(int id, String name, String displayName, Integer pieceTypeId) {
    }

    /**
     * One scheduled action occurrence (a clip) with its parameter values fully resolved.
     *
     * @param clipId     clip id
     * @param itemId     board item the step drives
     * @param startMs    start
     * @param durationMs length
     * @param action     resolved action, or {@code null} when the clip has none or it is unresolved
     * @param params     final parameter values by name (dynamic values the clip lacks are {@code null})
     * @param label      clip label, or {@code null}
     */
    public record ExecutionStep(String clipId, String itemId, long startMs, long durationMs, ExecutionAction action,
                                Map<String, Object> params, String label) {
    }

    /**
     * Resolves the document against the organization data.
     *
     * @param doc      the scene
     * @param context  pieces and actions of the organization
     * @param portById ports of the organization by id ({@code null} for an unknown id)
     * @return the plan
     */
    public static ExecutionPlan build(SceneDocument doc, SceneContext context, IntFunction<ShowPort> portById) {
        List<ShowIssue> issues = new ArrayList<>();
        List<ExecutionItem> items = new ArrayList<>();
        Map<String, Item> itemById = new HashMap<>();
        for (Item item : doc.items()) {
            itemById.put(item.id(), item);
            Optional<PieceRef> piece = pieceOf(context, item.pieceId());
            if (piece.isEmpty()) {
                issues.add(ShowIssue.warning("W_PIECE_MISSING",
                        "L'article " + item.pieceId() + " (element " + item.id() + ") no existeix al catàleg."));
            }
            List<ShowPort> ports = new ArrayList<>();
            for (Integer portId : item.ports()) {
                ShowPort port = portId == null ? null : portById.apply(portId);
                if (port == null) {
                    issues.add(ShowIssue.error("E_PORT_STALE", "El port " + portId + " assignat a «"
                            + piece.map(PieceRef::name).orElse(item.id()) + "» ja no existeix."));
                    continue;
                }
                ports.add(port);
            }
            items.add(new ExecutionItem(item.id(), item.pieceId(), piece.map(PieceRef::name).orElse(null), ports));
        }

        Map<String, String> itemIdByTrack = new HashMap<>();
        for (Track track : doc.tracks()) {
            itemIdByTrack.put(track.id(), track.itemId());
        }
        List<Clip> clips = new ArrayList<>(doc.clips());
        clips.sort((a, b) -> Long.compare(a.startMs(), b.startMs()));
        List<ExecutionStep> schedule = new ArrayList<>();
        for (Clip clip : clips) {
            String clipLabel = clip.label() != null ? clip.label() : clip.id();
            String itemId = itemIdByTrack.get(clip.trackId());
            if (itemId == null) {
                issues.add(ShowIssue.warning("W_TRACK_MISSING",
                        "El clip " + clipLabel + " apunta a una pista inexistent i s'ha omès.", clip.id(), null));
                continue;
            }
            Item item = itemById.get(itemId);
            Optional<PieceRef> piece = item == null ? Optional.empty() : pieceOf(context, item.pieceId());
            ExecutionAction action = null;
            Map<String, Object> params = new LinkedHashMap<>();
            if (clip.pieceTypeActionId() == null) {
                issues.add(ShowIssue.warning("W_CLIP_NO_ACTION",
                        "El clip " + clipLabel + " no té cap acció assignada.", clip.id(), null));
            } else {
                ActionRef def = piece.map(context::actionsOfPiece).orElse(List.of()).stream()
                        .filter(a -> a.id() == clip.pieceTypeActionId()).findFirst().orElse(null);
                if (def == null) {
                    issues.add(ShowIssue.warning("W_ACTION_UNRESOLVED", "No s'ha pogut resoldre l'acció "
                            + clip.pieceTypeActionId() + " del clip " + clipLabel + ".", clip.id(), null));
                } else {
                    action = new ExecutionAction(def.id(), def.name(), def.displayName(), def.typeId());
                    params = resolveParams(def, clip.paramValues(), clip.durationMs());
                }
            }
            schedule.add(new ExecutionStep(clip.id(), itemId, clip.startMs(), clip.durationMs(), action, params,
                    clip.label()));
        }
        return new ExecutionPlan(doc.durationMs(), doc.fps() == null ? 30 : doc.fps(), items, schedule, issues);
    }

    /**
     * Resolves a clip's final parameter values from the action definition and the clip's own values.
     *
     * @param action         the action definition
     * @param paramValues    the clip's dynamic values
     * @param clipDurationMs the clip length
     * @return values by parameter name, in definition order
     */
    static Map<String, Object> resolveParams(ActionRef action, Map<String, Object> paramValues, long clipDurationMs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (ActionParameterDto param : action.parameters()) {
            if (Boolean.TRUE.equals(param.getIsDuration())) {
                // The duration parameter (seconds) IS the clip length on the timeline.
                out.put(param.getName(), ShowNumbers.normalize(BigDecimal.valueOf(clipDurationMs).divide(BigDecimal.valueOf(1000))));
            } else if (Boolean.TRUE.equals(param.getDynamic())) {
                out.put(param.getName(), paramValues.get(param.getName()));
            } else {
                out.put(param.getName(), parseStaticValue(param));
            }
        }
        return out;
    }

    /**
     * Parses a static parameter value (string-encoded on the action definition) into a JSON value.
     *
     * @param param the parameter definition
     * @return the value, or {@code null} when the definition has none
     */
    static Object parseStaticValue(ActionParameterDto param) {
        String raw = param.getStaticValue();
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        if (param.getType() == null) {
            return raw;
        }
        switch (param.getType()) {
            case BOOLEAN -> {
                return "true".equals(raw);
            }
            case INTEGER, DECIMAL, PRICE -> {
                Number n = ShowNumbers.toNumber(raw);
                return n != null ? n : raw;
            }
            case MULTI_SELECT -> {
                String trimmed = raw.trim();
                if (trimmed.startsWith("[")) {
                    try {
                        Object parsed = JSON.readValue(trimmed, Object.class);
                        if (parsed instanceof List<?> list) {
                            return list;
                        }
                    } catch (JacksonException e) {
                        // fall through to raw
                    }
                }
                return raw;
            }
            default -> {
                return raw;
            }
        }
    }

    private static Optional<PieceRef> pieceOf(SceneContext context, Integer pieceId) {
        return pieceId == null ? Optional.empty() : context.pieceById(pieceId);
    }
}
