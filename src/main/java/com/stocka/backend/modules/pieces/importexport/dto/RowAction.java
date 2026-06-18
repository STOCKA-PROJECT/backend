package com.stocka.backend.modules.pieces.importexport.dto;

/**
 * Outcome computed for a single import row, both in dry-run (what <em>would</em> happen) and after
 * a real commit (what <em>did</em> happen).
 */
public enum RowAction {

    /** The row would create / created a new piece. */
    CREATE,

    /** The row would update / updated an existing piece matched by serial number. */
    UPDATE,

    /** The row was intentionally skipped (e.g. blank row). */
    SKIP,

    /** The row failed validation; see {@code errors} for the reasons. */
    ERROR
}
