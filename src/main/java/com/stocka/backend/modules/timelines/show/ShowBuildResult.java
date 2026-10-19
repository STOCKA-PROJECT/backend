package com.stocka.backend.modules.timelines.show;

import java.util.List;

/**
 * Outcome of {@link ShowPackageBuilder#build}.
 *
 * @param pkg      the package, or {@code null} when there is at least one error
 * @param errors   blocking findings, without duplicates
 * @param warnings non-blocking findings, without duplicates
 */
public record ShowBuildResult(ShowPackage pkg, List<ShowIssue> errors, List<ShowIssue> warnings) {

    /** @return whether the package could be built */
    public boolean ok() {
        return pkg != null;
    }
}
