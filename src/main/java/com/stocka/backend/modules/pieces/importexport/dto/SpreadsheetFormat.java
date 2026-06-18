package com.stocka.backend.modules.pieces.importexport.dto;

import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * File formats supported by the piece import/export endpoints. Both formats share the exact same
 * column schema; only the on-disk encoding differs.
 */
public enum SpreadsheetFormat {

    /** Comma-separated values (UTF-8 with BOM so Excel opens it correctly). */
    CSV("text/csv; charset=UTF-8", "csv"),

    /** Office Open XML spreadsheet ({@code .xlsx}). */
    XLSX("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx");

    private final String contentType;
    private final String extension;

    SpreadsheetFormat(String contentType, String extension) {
        this.contentType = contentType;
        this.extension = extension;
    }

    /**
     * @return the MIME type to send in the {@code Content-Type} header for downloads
     */
    public String contentType() {
        return contentType;
    }

    /**
     * @return the lowercase file extension without the leading dot
     */
    public String extension() {
        return extension;
    }

    /**
     * Resolves a request parameter into a {@link SpreadsheetFormat}, tolerating different casings.
     *
     * @param raw the {@code format} query parameter; {@code null} or blank defaults to {@link #CSV}
     * @return the matching format
     * @throws ResponseStatusException 400 when {@code raw} is neither {@code csv} nor {@code xlsx}
     */
    public static SpreadsheetFormat fromParam(String raw) {
        if (raw == null || raw.isBlank()) {
            return CSV;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "csv" -> CSV;
            case "xlsx", "excel" -> XLSX;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Formato no soportado: " + raw + " (usa csv o xlsx)");
        };
    }
}
