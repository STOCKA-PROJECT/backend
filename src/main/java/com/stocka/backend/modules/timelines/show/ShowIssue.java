package com.stocka.backend.modules.timelines.show;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.stocka.backend.modules.common.schema.Param;
import com.stocka.backend.modules.timelines.scene.SceneIssue;

/**
 * A finding raised while building or validating a show package, with the same codes and Catalan
 * texts as the timeline editor ({@code app/types/show.ts}). Errors block the export; warnings are
 * worth telling the user about.
 *
 * @param level   error or warning
 * @param code    stable code, e.g. {@code E_PARAM_REQUIRED} or {@code W_PORT_UNUSED}
 * @param message user-facing text (Catalan)
 * @param clipId  clip concerned, if any
 * @param port    port concerned, if any
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ShowIssue(
        @Param("error or warning") SceneIssue.Level level,
        @Param("Stable code, e.g. E_PARAM_REQUIRED or W_PORT_UNUSED") String code,
        @Param("What is wrong and where") String message,
        @Param("Clip concerned, if any") String clipId,
        @Param("Port concerned, if any") String port) {

    /** @return an error without clip or port */
    public static ShowIssue error(String code, String message) {
        return new ShowIssue(SceneIssue.Level.error, code, message, null, null);
    }

    /** @return an error about a clip and/or a port */
    public static ShowIssue error(String code, String message, String clipId, String port) {
        return new ShowIssue(SceneIssue.Level.error, code, message, clipId, port);
    }

    /** @return a warning without clip or port */
    public static ShowIssue warning(String code, String message) {
        return new ShowIssue(SceneIssue.Level.warning, code, message, null, null);
    }

    /** @return a warning about a clip and/or a port */
    public static ShowIssue warning(String code, String message, String clipId, String port) {
        return new ShowIssue(SceneIssue.Level.warning, code, message, clipId, port);
    }

    /** @return whether this finding blocks the export */
    public boolean isError() {
        return level == SceneIssue.Level.error;
    }

    /** @return the key the builder uses to drop duplicates: code and message */
    public String dedupeKey() {
        return code + "|" + message;
    }
}
