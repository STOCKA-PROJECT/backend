package com.stocka.backend.modules.timelines.scene;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.stocka.backend.modules.common.schema.Param;

/**
 * Typed view of the timeline editor document ({@code timeline-editor/app/types/editor.ts},
 * {@code schemaVersion} 1). Geometry is in board pixels ({@link #PX_PER_CM} px = 1 cm); times in
 * milliseconds from the start of the show.
 *
 * <p>The editor owns the schema. This model only names the fields the server understands;
 * {@link SceneJsonCodec} carries any other field through untouched.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SceneDocument(
        @Param("Schema version, always 1") int schemaVersion,
        @Param("Board size") Board board,
        @Param("Layers") List<Layer> layers,
        @Param("Pieces placed on the board") List<Item> items,
        @Param("Tracks (one or more per item)") List<Track> tracks,
        @Param("Clips: actions played on a track") List<Clip> clips,
        @Param("Total length in milliseconds") long durationMs,
        @Param("Editor frame rate") Integer fps,
        @Param("Order of the items along each LED strip port: port id → item ids") Map<String, List<String>> stripOrder
) {

    /** Current schema version. */
    public static final int SCHEMA_VERSION = 1;
    /** Board pixels per centimetre. */
    public static final double PX_PER_CM = 10;
    /** Default size of a new item, in centimetres. */
    public static final double DEFAULT_ITEM_SIZE_CM = 10;
    /** Default clip length. */
    public static final long DEFAULT_CLIP_MS = 2000;
    /** Default show length. */
    public static final long DEFAULT_DURATION_MS = 60_000;

    public SceneDocument {
        layers = layers == null ? new ArrayList<>() : new ArrayList<>(layers);
        items = items == null ? new ArrayList<>() : new ArrayList<>(items);
        tracks = tracks == null ? new ArrayList<>() : new ArrayList<>(tracks);
        clips = clips == null ? new ArrayList<>() : new ArrayList<>(clips);
        stripOrder = stripOrder == null ? new LinkedHashMap<>() : new LinkedHashMap<>(stripOrder);
        if (board == null) board = new Board(1280, 720, null);
    }

    /** @return the empty document the editor would create, with one layer */
    public static SceneDocument empty(long durationMs, Board board) {
        List<Layer> layers = new ArrayList<>();
        layers.add(new Layer("layer-1", "Capa 1", 0, true, false, 1));
        return new SceneDocument(SCHEMA_VERSION, board == null ? new Board(1280, 720, null) : board, layers,
                List.of(), List.of(), List.of(), durationMs <= 0 ? DEFAULT_DURATION_MS : durationMs, 30,
                Map.of());
    }

    /** @return centimetres for a pixel value */
    public static double toCm(double px) {
        return px / PX_PER_CM;
    }

    /** @return pixels for a centimetre value */
    public static double toPx(double cm) {
        return cm * PX_PER_CM;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Board(
            @Param("Width in board pixels (cm × 10)") double width,
            @Param("Height in board pixels (cm × 10)") double height,
            @Param("Background colour or image reference") String background) {
    }

    public record Layer(
            @Param("Layer id") String id,
            @Param("Name") String name,
            @Param("Stacking order") int z,
            @Param("Visible") boolean visible,
            @Param("Locked") boolean locked,
            @Param("Opacity 0..1") double opacity) {
    }

    public record LedPoint(double x, double y) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(
            @Param("Item id") String id,
            @Param("Piece id") Integer pieceId,
            @Param("Layer id") String layerId,
            @Param("X in board pixels") double x,
            @Param("Y in board pixels") double y,
            @Param("Width in board pixels") double width,
            @Param("Height in board pixels") double height,
            @Param("Rotation in degrees") double rotation,
            @Param("Piece attribute driving the width (cm)") String widthAttr,
            @Param("Piece attribute driving the height (cm)") String heightAttr,
            @Param("Hardware port ids bound to the item") List<Integer> ports,
            @Param("LED polyline inside the item, fractions of its size") List<LedPoint> ledPath) {
        public Item {
            ports = ports == null ? new ArrayList<>() : new ArrayList<>(ports);
        }
    }

    public record Track(
            @Param("Track id") String id,
            @Param("Item id") String itemId,
            @Param("Name") String name) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Clip(
            @Param("Clip id") String id,
            @Param("Track id") String trackId,
            @Param("Piece-type action id, null for a placeholder") Integer pieceTypeActionId,
            @Param("Start in milliseconds") long startMs,
            @Param("Length in milliseconds (also the action's duration parameter, in seconds)") long durationMs,
            @Param("Dynamic parameter values by name") Map<String, Object> paramValues,
            @Param("Label") String label) {
        public Clip {
            paramValues = paramValues == null ? new LinkedHashMap<>() : new LinkedHashMap<>(paramValues);
        }

        /** @return end of the clip in milliseconds */
        public long endMs() {
            return startMs + durationMs;
        }
    }
}
