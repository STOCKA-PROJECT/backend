package com.stocka.backend.modules.timelines.scene;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.stocka.backend.modules.common.schema.Param;

/**
 * One edit of a scene, applied by {@link SceneEditor}. The JSON shape is {@code {"op": "<name>", …}}.
 *
 * <p>Sizes and positions are in centimetres (the document stores board pixels); times in
 * milliseconds. References accept ids and, where stated, names.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "op")
@JsonSubTypes({
        @JsonSubTypes.Type(value = SceneOp.AddLayer.class, name = "add_layer"),
        @JsonSubTypes.Type(value = SceneOp.AddItem.class, name = "add_item"),
        @JsonSubTypes.Type(value = SceneOp.UpdateItem.class, name = "update_item"),
        @JsonSubTypes.Type(value = SceneOp.SetItemPorts.class, name = "set_item_ports"),
        @JsonSubTypes.Type(value = SceneOp.RemoveItem.class, name = "remove_item"),
        @JsonSubTypes.Type(value = SceneOp.AddTrack.class, name = "add_track"),
        @JsonSubTypes.Type(value = SceneOp.RenameTrack.class, name = "rename_track"),
        @JsonSubTypes.Type(value = SceneOp.RemoveTrack.class, name = "remove_track"),
        @JsonSubTypes.Type(value = SceneOp.AddClip.class, name = "add_clip"),
        @JsonSubTypes.Type(value = SceneOp.UpdateClip.class, name = "update_clip"),
        @JsonSubTypes.Type(value = SceneOp.MoveClip.class, name = "move_clip"),
        @JsonSubTypes.Type(value = SceneOp.RemoveClip.class, name = "remove_clip"),
        @JsonSubTypes.Type(value = SceneOp.SetBoard.class, name = "set_board"),
        @JsonSubTypes.Type(value = SceneOp.SetStripOrder.class, name = "set_strip_order"),
        @JsonSubTypes.Type(value = SceneOp.SetSchedule.class, name = "set_schedule")
})
public sealed interface SceneOp permits SceneOp.AddLayer, SceneOp.AddItem, SceneOp.UpdateItem, SceneOp.SetItemPorts,
        SceneOp.RemoveItem, SceneOp.AddTrack, SceneOp.RenameTrack, SceneOp.RemoveTrack, SceneOp.AddClip,
        SceneOp.UpdateClip, SceneOp.MoveClip, SceneOp.RemoveClip, SceneOp.SetBoard,
        SceneOp.SetStripOrder, SceneOp.SetSchedule {

    /** Adds a layer on top. */
    record AddLayer(@Param(value = "Layer name", required = true) String name) implements SceneOp {
    }

    /** Places a piece on the board and creates its first track. */
    record AddItem(
            @Param("Piece id (or use pieceName)") Integer pieceId,
            @Param("Piece name, must match exactly one piece of the organization") String pieceName,
            @Param("Layer id; default the first layer") String layerId,
            @Param("X in cm; omitted = placed automatically") Double x,
            @Param("Y in cm; omitted = placed automatically") Double y,
            @Param("Width in cm (default 10)") Double width,
            @Param("Height in cm (default 10)") Double height,
            @Param("Rotation in degrees (default 0)") Double rotation,
            @Param("Hardware ports to bind, by id or name (hardware organizations only)") List<String> ports) implements SceneOp {
    }

    /** Moves, resizes or re-layers an item. */
    record UpdateItem(
            @Param(value = "Item id", required = true) String itemId,
            @Param("New layer id") String layerId,
            @Param("New X in cm") Double x,
            @Param("New Y in cm") Double y,
            @Param("New width in cm") Double width,
            @Param("New height in cm") Double height,
            @Param("New rotation in degrees") Double rotation) implements SceneOp {
    }

    /** Replaces the ports bound to an item. */
    record SetItemPorts(
            @Param(value = "Item id", required = true) String itemId,
            @Param(value = "Ports by id or name; empty list unbinds", required = true) List<String> ports) implements SceneOp {
    }

    /** Removes an item with its tracks and clips. */
    record RemoveItem(@Param(value = "Item id", required = true) String itemId) implements SceneOp {
    }

    /** Adds a track to an item. */
    record AddTrack(
            @Param(value = "Item id", required = true) String itemId,
            @Param("Track name; default the piece name") String name) implements SceneOp {
    }

    /** Renames a track. */
    record RenameTrack(
            @Param(value = "Track id", required = true) String trackId,
            @Param(value = "New name", required = true) String name) implements SceneOp {
    }

    /** Removes a track and its clips. */
    record RemoveTrack(@Param(value = "Track id", required = true) String trackId) implements SceneOp {
    }

    /** Adds a clip to a track (or to the first track of an item). */
    record AddClip(
            @Param("Item id: the clip goes on the item's first track (created if missing)") String itemId,
            @Param("Track id (alternative to itemId)") String trackId,
            @Param(value = "Action name or id among the actions of the item's piece types", required = true) String action,
            @Param("Start in ms; omitted = right after the last clip of the track (or 0)") Long startMs,
            @Param("Start right after this clip id (alternative to startMs)") String after,
            @Param("Gap in ms after the `after` clip (default 0)") Long gapMs,
            @Param("Length in ms (default 2000)") Long durationMs,
            @Param("Dynamic parameter values by name; static and duration parameters must not be sent") Map<String, Object> params,
            @Param("Label") String label) implements SceneOp {
    }

    /** Changes a clip's action, length, parameters or label. */
    record UpdateClip(
            @Param(value = "Clip id", required = true) String clipId,
            @Param("New action name or id") String action,
            @Param("New length in ms") Long durationMs,
            @Param("Parameter values to set (merged unless replaceParams)") Map<String, Object> params,
            @Param("Replace all parameter values instead of merging") Boolean replaceParams,
            @Param("New label") String label) implements SceneOp {
    }

    /** Moves a clip in time and/or to another track. */
    record MoveClip(
            @Param(value = "Clip id", required = true) String clipId,
            @Param("New start in ms") Long startMs,
            @Param("New track id") String trackId) implements SceneOp {
    }

    /** Removes a clip. */
    record RemoveClip(@Param(value = "Clip id", required = true) String clipId) implements SceneOp {
    }

    /** Sets the board size. */
    record SetBoard(
            @Param("Width in cm") Double width,
            @Param("Height in cm") Double height,
            @Param("Background") String background) implements SceneOp {
    }

    /** Sets when the physical button starts the show; {@code null} removes the schedule (the button always works). */
    record SetSchedule(
            @Param("Button schedule: rule allow|block + windows [{days, from, to}]; omit or null to remove it")
            SceneDocument.Schedule schedule) implements SceneOp {
    }

    /** Orders the items along a LED strip port. */
    record SetStripOrder(
            @Param(value = "LED strip port id or name", required = true) String port,
            @Param(value = "Item ids in strip order (first LED first)", required = true) List<String> itemIds) implements SceneOp {
    }
}
