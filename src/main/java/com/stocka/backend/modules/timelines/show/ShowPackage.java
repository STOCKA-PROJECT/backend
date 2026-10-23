package com.stocka.backend.modules.timelines.show;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.stocka.backend.modules.common.schema.Param;
import com.stocka.backend.modules.timelines.scene.SceneDocument.Schedule;

/**
 * The {@code stocka-show} v3 package: what the Raspberry Pi player executes. Format and rules are
 * documented in {@code rpi4-system/contract/README.md} and checked by {@link ShowValidator}; the
 * editor builds the very same structure in {@code utils/showPackage.ts}.
 *
 * @param format     always {@value #FORMAT}
 * @param version    always {@value #VERSION}
 * @param meta       name, origin and hash (outside the hash itself)
 * @param board      board id, e.g. {@code rpi4b}
 * @param mode       how the show repeats
 * @param hardware   one entry per port, keyed by name: {@code kind}, {@code pin} when it has one, config keys
 * @param timeline   events in time order
 * @param durationMs length of one cycle in milliseconds
 * @param assets     media files the events reference
 */
public record ShowPackage(
        @Param("Always stocka-show") String format,
        @Param("Always 3") int version,
        @Param("Name, origin and hash of the show") ShowMeta meta,
        @Param("Board id, e.g. rpi4b") String board,
        @Param("How the show repeats") ShowMode mode,
        @Param("One entry per port, keyed by port name: kind, pin and config keys") Map<String, Map<String, Object>> hardware,
        @Param("Events in time order") List<ShowEvent> timeline,
        @Param("Length of one cycle in ms") long durationMs,
        @Param("Media files the events reference") ShowAssets assets) {

    /** Package format id. */
    public static final String FORMAT = "stocka-show";
    /** Package format version. */
    public static final int VERSION = 3;

    /**
     * Metadata about the export.
     *
     * @param name       show name (the timeline name)
     * @param timelineId source timeline, or {@code null}
     * @param exportedAt ISO-8601 instant of the export
     * @param hash       {@code sha256:} + hex digest of the canonical JSON of the show description (meta excluded)
     * @param assets     origin of every asset, when known (outside the hash)
     */
    public record ShowMeta(
            @Param("Show name") String name,
            @Param("Source timeline id") Integer timelineId,
            @Param("ISO-8601 instant of the export") String exportedAt,
            @Param("sha256:<hex> of the canonical JSON of board, mode, hardware, timeline, durationMs and assets") String hash,
            @Param("Origin of every asset (attachment), outside the hash")
            @JsonInclude(JsonInclude.Include.NON_NULL) List<ShowAssetRef> assets) {
    }

    /**
     * How the show repeats and when the button starts it.
     *
     * @param on       {@code once}, {@code loop} or {@code pulse}
     * @param cycles   number of cycles
     * @param schedule button schedule, or {@code null} (omitted from the JSON and so from the hash)
     */
    public record ShowMode(
            @Param("once, loop or pulse") String on,
            @Param("Number of cycles") int cycles,
            @Param("Button schedule: rule allow|block + windows [{days, from, to}]; absent = always")
            @JsonInclude(JsonInclude.Include.NON_NULL) Schedule schedule) {

        /** Play once. */
        public static final ShowMode DEFAULT = new ShowMode("once", 1);

        /**
         * @param on     {@code once}, {@code loop} or {@code pulse}
         * @param cycles number of cycles
         */
        public ShowMode(String on, int cycles) {
            this(on, cycles, null);
        }

        /** @return this mode with another schedule */
        public ShowMode withSchedule(Schedule schedule) {
            return new ShowMode(on, cycles, schedule);
        }
    }

    /**
     * One scheduled action.
     *
     * @param t      milliseconds from the start of the cycle
     * @param target port name
     * @param action action name of the port's kind
     * @param params parameters coerced to the catalog types
     */
    public record ShowEvent(
            @Param("Milliseconds from the start of the cycle") long t,
            @Param("Port name") String target,
            @Param("Action name") String action,
            @Param("Parameters, coerced to the catalog types") Map<String, Object> params) {
    }

    /**
     * Media files the events reference, by asset name.
     *
     * @param audio audio asset names
     */
    public record ShowAssets(@Param("Audio asset names") List<String> audio) {
    }
}
