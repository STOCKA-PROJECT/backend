package com.stocka.backend.modules.timelines.dto;

import java.util.List;

import com.stocka.backend.modules.common.schema.Param;
import com.stocka.backend.modules.timelines.scene.SceneIssue;

/**
 * Validation report of a scene.
 *
 * @param valid    whether there is no error (warnings do not count)
 * @param errors   number of errors
 * @param warnings number of warnings
 * @param issues   every finding, errors first
 */
public record SceneValidationDto(
        @Param("Whether the scene has no errors") boolean valid,
        @Param("Number of errors") int errors,
        @Param("Number of warnings") int warnings,
        @Param("Findings, errors first") List<SceneIssue> issues) {

    public static SceneValidationDto of(List<SceneIssue> issues) {
        int errors = (int) issues.stream().filter(SceneIssue::isError).count();
        return new SceneValidationDto(errors == 0, errors, issues.size() - errors, issues);
    }
}
