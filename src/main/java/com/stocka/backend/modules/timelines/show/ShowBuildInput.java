package com.stocka.backend.modules.timelines.show;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import com.stocka.backend.modules.contract.OutputCatalog;
import com.stocka.backend.modules.timelines.scene.SceneContext;
import com.stocka.backend.modules.timelines.scene.SceneDocument;
import com.stocka.backend.modules.timelines.show.ShowPackage.ShowMode;

/**
 * Everything {@link ShowPackageBuilder} needs to turn a scene into a package. Mirrors
 * {@code BuildShowInput} in the editor's {@code utils/showPackage.ts}.
 *
 * @param doc          the scene
 * @param context      pieces and actions of the organization
 * @param orgPorts     every port of the organization: all of them go to {@code hardware}, assigned or not
 * @param audioByPiece AUDIO attachments per piece id for the pieces whose files are known. When given,
 *                     every audio asset of those pieces must be one of its files (an error otherwise)
 *                     and {@code meta.assets} lists where each file comes from. Pieces absent from the
 *                     map are not checked; {@code null} disables the check and {@code meta.assets}
 * @param ledLengthOf  LEDs of a piece (its length attribute) by piece id, {@code null} when unknown; may be {@code null}
 * @param timelineName show name ({@code Show} when blank)
 * @param timelineId   source timeline id, may be {@code null}
 * @param board        board id
 * @param mode         repeat mode
 * @param now          clock for {@code meta.exportedAt}, as an ISO-8601 instant
 */
public record ShowBuildInput(
        SceneDocument doc,
        SceneContext context,
        List<ShowPort> orgPorts,
        Map<Integer, List<ShowAudioFile>> audioByPiece,
        Function<Integer, Integer> ledLengthOf,
        String timelineName,
        Integer timelineId,
        String board,
        ShowMode mode,
        Supplier<String> now) {

    /** {@code 2026-10-12T00:00:00.000Z}: what {@code Date.prototype.toISOString} writes. */
    public static final DateTimeFormatter ISO_MILLIS =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    public ShowBuildInput {
        orgPorts = orgPorts == null ? List.of() : List.copyOf(orgPorts);
        board = board == null || board.isBlank() ? OutputCatalog.DEFAULT_BOARD : board;
        mode = mode == null ? ShowMode.DEFAULT : mode;
        now = now == null ? () -> ISO_MILLIS.format(Instant.now()) : now;
    }

    /**
     * Minimal input: the scene, the organization data and its ports; nothing else is checked.
     *
     * @param doc      the scene
     * @param context  pieces and actions
     * @param orgPorts ports of the organization
     * @return an input with default name, board, mode and clock
     */
    public static ShowBuildInput of(SceneDocument doc, SceneContext context, List<ShowPort> orgPorts) {
        return new ShowBuildInput(doc, context, orgPorts, null, null, null, null, null, null, null);
    }

    /** @return a copy checking audio asset names against {@code audioByPiece} */
    public ShowBuildInput withAudioByPiece(Map<Integer, List<ShowAudioFile>> audioByPiece) {
        return new ShowBuildInput(doc, context, orgPorts, audioByPiece, ledLengthOf, timelineName, timelineId, board, mode, now);
    }

    /** @return a copy laying strips out with {@code ledLengthOf} */
    public ShowBuildInput withLedLengthOf(Function<Integer, Integer> ledLengthOf) {
        return new ShowBuildInput(doc, context, orgPorts, audioByPiece, ledLengthOf, timelineName, timelineId, board, mode, now);
    }

    /** @return a copy naming the show after a timeline */
    public ShowBuildInput withTimeline(String timelineName, Integer timelineId) {
        return new ShowBuildInput(doc, context, orgPorts, audioByPiece, ledLengthOf, timelineName, timelineId, board, mode, now);
    }

    /** @return a copy targeting another board */
    public ShowBuildInput withBoard(String board) {
        return new ShowBuildInput(doc, context, orgPorts, audioByPiece, ledLengthOf, timelineName, timelineId, board, mode, now);
    }

    /** @return a copy with another repeat mode */
    public ShowBuildInput withMode(ShowMode mode) {
        return new ShowBuildInput(doc, context, orgPorts, audioByPiece, ledLengthOf, timelineName, timelineId, board, mode, now);
    }

    /** @return a copy with a fixed clock (tests) */
    public ShowBuildInput withNow(Supplier<String> now) {
        return new ShowBuildInput(doc, context, orgPorts, audioByPiece, ledLengthOf, timelineName, timelineId, board, mode, now);
    }
}
