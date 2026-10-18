package com.stocka.backend.modules.timelines.scene;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.stocka.backend.modules.common.schema.Param;

/**
 * A validation finding on a scene. Errors must be fixed before the show can be exported; warnings
 * are worth telling the user about.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SceneIssue(
        @Param("error or warning") Level level,
        @Param("Stable code, e.g. E_ACTION_UNKNOWN or W_CLIP_PAST_END") String code,
        @Param("What is wrong and where") String message,
        @Param("Item concerned, if any") String itemId,
        @Param("Clip concerned, if any") String clipId) {

    public enum Level { error, warning }

    public static SceneIssue error(String code, String message, String itemId, String clipId) {
        return new SceneIssue(Level.error, code, message, itemId, clipId);
    }

    public static SceneIssue warning(String code, String message, String itemId, String clipId) {
        return new SceneIssue(Level.warning, code, message, itemId, clipId);
    }

    public boolean isError() {
        return level == Level.error;
    }
}
