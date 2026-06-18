package com.stocka.backend.modules.pieces.importexport.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Caps for the synchronous piece import/export endpoints, loaded from
 * {@code stocka.pieces.import-export.*}. They bound the amount of work a single request can do so
 * a huge file cannot exhaust memory or hold a transaction open indefinitely.
 */
@ConfigurationProperties(prefix = "stocka.pieces.import-export")
public class PieceImportExportProperties {

    /** Maximum number of data rows accepted in a single import file. */
    private int maxImportRows = 5_000;

    /** Maximum number of pieces a single export request may serialize. */
    private int maxExportRows = 10_000;

    public int getMaxImportRows() {
        return maxImportRows;
    }

    public void setMaxImportRows(int maxImportRows) {
        this.maxImportRows = maxImportRows;
    }

    public int getMaxExportRows() {
        return maxExportRows;
    }

    public void setMaxExportRows(int maxExportRows) {
        this.maxExportRows = maxExportRows;
    }
}
