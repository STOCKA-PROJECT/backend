package com.stocka.backend.modules.pieces.importexport.dto;

import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Strategy applied to each import row when a piece with the same serial number already exists in
 * the organization.
 */
public enum ImportMode {

    /**
     * Every row creates a new piece. A row whose serial number already exists in the organization
     * is reported as an error (the per-org uniqueness rule would reject it).
     */
    CREATE,

    /**
     * A row whose non-blank serial number matches an existing piece updates that piece; otherwise
     * the row creates a new piece. Only the columns present in the file are touched and blank cells
     * are treated as "leave unchanged" — the import never clears or detaches fields.
     */
    UPSERT;

    /**
     * Resolves a request parameter into an {@link ImportMode}, tolerating different casings.
     *
     * @param raw the {@code mode} query parameter; {@code null} or blank defaults to {@link #CREATE}
     * @return the matching mode
     * @throws ResponseStatusException 400 when {@code raw} is neither {@code create} nor {@code upsert}
     */
    public static ImportMode fromParam(String raw) {
        if (raw == null || raw.isBlank()) {
            return CREATE;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "create" -> CREATE;
            case "upsert" -> UPSERT;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Modo de importación no soportado: " + raw + " (usa create o upsert)");
        };
    }
}
