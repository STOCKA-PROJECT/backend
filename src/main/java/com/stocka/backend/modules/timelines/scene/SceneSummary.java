package com.stocka.backend.modules.timelines.scene;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.stocka.backend.modules.common.schema.Param;
import com.stocka.backend.modules.timelines.scene.SceneContext.ActionRef;
import com.stocka.backend.modules.timelines.scene.SceneContext.PieceRef;
import com.stocka.backend.modules.timelines.scene.SceneDocument.Clip;
import com.stocka.backend.modules.timelines.scene.SceneDocument.Item;
import com.stocka.backend.modules.timelines.scene.SceneDocument.Track;

/**
 * Compact, human-oriented view of a scene: items with piece names and ports, clips in time order
 * with action names, sizes in centimetres. Much cheaper to read than the raw document.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SceneSummary(
        @Param("Total length in ms") long durationMs,
        @Param("Board width in cm") double boardWidthCm,
        @Param("Board height in cm") double boardHeightCm,
        @Param("Layers") List<LayerSummary> layers,
        @Param("Items on the board") List<ItemSummary> items,
        @Param("Tracks") List<TrackSummary> tracks,
        @Param("Clips in time order") List<ClipSummary> clips,
        @Param("Button schedule (absent = the button always works)") SceneDocument.Schedule schedule,
        @Param("Validation findings") List<SceneIssue> issues) {

    public record LayerSummary(@Param("Id") String id, @Param("Name") String name, @Param("Order") int z) {
    }

    public record ItemSummary(
            @Param("Item id") String id,
            @Param("Piece id") Integer pieceId,
            @Param("Piece name") String pieceName,
            @Param("Piece types") List<String> types,
            @Param("Bound port names") List<String> ports,
            @Param("Layer id") String layerId,
            @Param("X in cm") double xCm,
            @Param("Y in cm") double yCm,
            @Param("Width in cm") double widthCm,
            @Param("Height in cm") double heightCm) {
    }

    public record TrackSummary(@Param("Track id") String id, @Param("Item id") String itemId, @Param("Name") String name) {
    }

    public record ClipSummary(
            @Param("Clip id") String id,
            @Param("Track id") String trackId,
            @Param("Item id") String itemId,
            @Param("Piece name") String pieceName,
            @Param("Action id") Integer actionId,
            @Param("Action name") String action,
            @Param("Start in ms") long startMs,
            @Param("Length in ms") long durationMs,
            @Param("Parameter values") Map<String, Object> params,
            @Param("Label") String label) {
    }

    /** @return the summary of {@code doc} with {@code issues} attached */
    public static SceneSummary of(SceneDocument doc, SceneContext context, List<SceneIssue> issues) {
        Map<String, Item> items = new HashMap<>();
        Map<String, String> pieceNames = new HashMap<>();
        List<ItemSummary> itemSummaries = new ArrayList<>();
        for (Item item : doc.items()) {
            items.put(item.id(), item);
            Optional<PieceRef> piece = item.pieceId() == null ? Optional.empty() : context.pieceById(item.pieceId());
            String name = piece.map(PieceRef::name).orElse("(missing piece " + item.pieceId() + ")");
            pieceNames.put(item.id(), name);
            List<String> ports = new ArrayList<>();
            for (Integer portId : item.ports()) {
                ports.add(context.portById(portId).map(p -> p.name()).orElse("#" + portId));
            }
            itemSummaries.add(new ItemSummary(item.id(), item.pieceId(), name,
                    piece.map(PieceRef::typeNames).orElse(List.of()), ports, item.layerId(),
                    round(SceneDocument.toCm(item.x())), round(SceneDocument.toCm(item.y())),
                    round(SceneDocument.toCm(item.width())), round(SceneDocument.toCm(item.height()))));
        }
        Map<String, Track> tracks = new HashMap<>();
        List<TrackSummary> trackSummaries = new ArrayList<>();
        for (Track track : doc.tracks()) {
            tracks.put(track.id(), track);
            trackSummaries.add(new TrackSummary(track.id(), track.itemId(), track.name()));
        }
        List<Clip> sorted = new ArrayList<>(doc.clips());
        sorted.sort((a, b) -> Long.compare(a.startMs(), b.startMs()));
        List<ClipSummary> clipSummaries = new ArrayList<>();
        for (Clip clip : sorted) {
            Track track = tracks.get(clip.trackId());
            String itemId = track == null ? null : track.itemId();
            String actionName = clip.pieceTypeActionId() == null ? null
                    : context.actionById(clip.pieceTypeActionId()).map(ActionRef::name).orElse("(missing action)");
            clipSummaries.add(new ClipSummary(clip.id(), clip.trackId(), itemId, itemId == null ? null : pieceNames.get(itemId),
                    clip.pieceTypeActionId(), actionName, clip.startMs(), clip.durationMs(), clip.paramValues(), clip.label()));
        }
        List<LayerSummary> layers = new ArrayList<>();
        doc.layers().forEach(l -> layers.add(new LayerSummary(l.id(), l.name(), l.z())));
        return new SceneSummary(doc.durationMs(), round(SceneDocument.toCm(doc.board().width())),
                round(SceneDocument.toCm(doc.board().height())), layers, itemSummaries, trackSummaries, clipSummaries,
                doc.schedule(), issues);
    }

    private static double round(double value) {
        return Math.round(value * 10) / 10.0;
    }
}
