package com.stocka.backend.modules.pieces.importexport.dto;

import java.util.List;

/**
 * Result of a piece import request, returned by both the dry-run preview and the real commit.
 *
 * <p>In dry-run ({@code dryRun == true}) no data is written; {@code created}/{@code updated} count
 * what <em>would</em> happen. After a successful commit they count what <em>did</em> happen. When a
 * commit is rejected (blocking errors), {@code applied == false} and nothing was written.
 *
 * @param dryRun     whether this was a validation-only preview
 * @param applied    whether changes were actually persisted (always {@code false} for dry-run and
 *                   for rejected commits)
 * @param mode       the import mode that was used
 * @param totalRows  number of data rows processed
 * @param created    rows that created (or would create) a new piece
 * @param updated    rows that updated (or would update) an existing piece
 * @param skipped    rows skipped (blank rows)
 * @param failed     rows that failed validation
 * @param rows       per-row outcomes, in file order
 * @param warnings   non-blocking, file-level messages (e.g. unknown columns ignored)
 */
public record PieceImportReportDto(
        boolean dryRun,
        boolean applied,
        ImportMode mode,
        int totalRows,
        int created,
        int updated,
        int skipped,
        int failed,
        List<RowResultDto> rows,
        List<String> warnings
) {

    /**
     * Builds a report from the per-row outcomes, deriving the aggregate counters.
     *
     * @param dryRun   whether this was a validation-only preview
     * @param applied  whether changes were persisted
     * @param mode     import mode used
     * @param rows     per-row outcomes
     * @param warnings file-level warnings
     * @return the assembled report
     */
    public static PieceImportReportDto of(boolean dryRun, boolean applied, ImportMode mode,
                                          List<RowResultDto> rows, List<String> warnings) {
        int created = 0;
        int updated = 0;
        int skipped = 0;
        int failed = 0;
        for (RowResultDto r : rows) {
            switch (r.action()) {
                case CREATE -> created++;
                case UPDATE -> updated++;
                case SKIP -> skipped++;
                case ERROR -> failed++;
            }
        }
        return new PieceImportReportDto(dryRun, applied, mode, rows.size(),
                created, updated, skipped, failed, rows, List.copyOf(warnings));
    }

    /**
     * @return {@code true} when at least one row failed validation
     */
    public boolean hasErrors() {
        return failed > 0;
    }
}
