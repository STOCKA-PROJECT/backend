package com.stocka.backend.modules.pieces.importexport.service;

import com.stocka.backend.modules.pieces.importexport.dto.PieceImportReportDto;

/**
 * Thrown from the transactional commit when a row fails so the surrounding transaction rolls back
 * (all-or-nothing). Carries the partial report so the controller can answer {@code 422} with the
 * row-level detail instead of a generic error.
 */
public class PieceImportException extends RuntimeException {

    private final transient PieceImportReportDto report;

    /**
     * @param report the report describing why the import was rejected
     */
    public PieceImportException(PieceImportReportDto report) {
        super("Import rejected: " + report.failed() + " failed row(s)");
        this.report = report;
    }

    /**
     * @return the report to return to the client
     */
    public PieceImportReportDto getReport() {
        return report;
    }
}
