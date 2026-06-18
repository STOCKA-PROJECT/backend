package com.stocka.backend.modules.pieces.importexport.dto;

import java.util.List;

/**
 * Per-row outcome inside a {@link PieceImportReportDto}.
 *
 * @param rowNumber    1-based index of the data row in the uploaded file (the header row is row 0
 *                     and is not reported)
 * @param action       what happened (or would happen) for this row
 * @param pieceId      id of the affected piece; {@code null} in dry-run and for failed rows
 * @param serialNumber the row's serial number, echoed back to help the user locate it; may be
 *                     {@code null}
 * @param name         the row's name, echoed back for readability; may be {@code null}
 * @param errors       human-readable (Spanish) validation messages; empty unless
 *                     {@code action == }{@link RowAction#ERROR}
 */
public record RowResultDto(
        int rowNumber,
        RowAction action,
        Integer pieceId,
        String serialNumber,
        String name,
        List<String> errors
) {

    /**
     * Builds a successful row outcome.
     *
     * @param rowNumber    1-based data-row index
     * @param action       {@link RowAction#CREATE} or {@link RowAction#UPDATE}
     * @param pieceId      affected piece id, or {@code null} in dry-run
     * @param serialNumber row serial number (nullable)
     * @param name         row name (nullable)
     * @return the populated result
     */
    public static RowResultDto ok(int rowNumber, RowAction action, Integer pieceId,
                                  String serialNumber, String name) {
        return new RowResultDto(rowNumber, action, pieceId, serialNumber, name, List.of());
    }

    /**
     * Builds a failed row outcome.
     *
     * @param rowNumber    1-based data-row index
     * @param serialNumber row serial number (nullable)
     * @param name         row name (nullable)
     * @param errors       validation messages explaining the failure
     * @return the populated result
     */
    public static RowResultDto error(int rowNumber, String serialNumber, String name,
                                     List<String> errors) {
        return new RowResultDto(rowNumber, RowAction.ERROR, null, serialNumber, name, errors);
    }
}
