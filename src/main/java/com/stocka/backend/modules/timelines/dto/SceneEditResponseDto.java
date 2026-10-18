package com.stocka.backend.modules.timelines.dto;

import java.util.List;

import com.stocka.backend.modules.timelines.scene.SceneIssue;
import com.stocka.backend.modules.timelines.scene.SceneSummary;

/**
 * Response of the scene operations endpoint.
 *
 * @param scene      the saved scene (with the new {@code version} and the full document)
 * @param createdIds ids of the layers, items, tracks and clips the batch created, in order
 * @param issues     validation findings after the edit
 * @param summary    compact view of the scene after the edit
 */
public record SceneEditResponseDto(
        TimelineSceneResponseDto scene,
        List<String> createdIds,
        List<SceneIssue> issues,
        SceneSummary summary
) {
}
