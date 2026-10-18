package com.stocka.backend.modules.timelines.dto;

import java.util.List;

import com.stocka.backend.modules.timelines.scene.SceneOp;

/**
 * Body of {@code POST /organizations/{orgSlug}/timelines/{timelineId}/scene/ops}.
 *
 * @param ops             operations in order; each is {@code {"op": "<name>", …}}
 * @param expectedVersion scene version the client read; required when the scene exists
 */
public record SceneOpsRequestDto(List<SceneOp> ops, Integer expectedVersion) {
}
