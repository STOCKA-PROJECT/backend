package com.stocka.backend.modules.pieces.importexport.controller;

import java.io.IOException;
import java.util.Locale;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.stocka.backend.modules.common.error.ApiException;
import com.stocka.backend.modules.common.error.ErrorCodes;
import com.stocka.backend.modules.organizations.service.OrganizationResolver;
import com.stocka.backend.modules.pieces.entity.PieceStatus;
import com.stocka.backend.modules.pieces.importexport.dto.ImportMode;
import com.stocka.backend.modules.pieces.importexport.dto.PieceImportReportDto;
import com.stocka.backend.modules.pieces.importexport.dto.SpreadsheetFormat;
import com.stocka.backend.modules.pieces.importexport.service.PieceExportService;
import com.stocka.backend.modules.pieces.importexport.service.PieceImportException;
import com.stocka.backend.modules.pieces.importexport.service.PieceImportService;

/**
 * Bulk import/export of an organization's pieces (articles). Shares the
 * {@code /organizations/{orgSlug}/pieces} base path with {@code PieceController}; the literal
 * {@code /export} and {@code /import} segments take precedence over the {@code /{pieceId}} pattern.
 */
@RestController
@RequestMapping("/organizations/{orgSlug}/pieces")
public class PieceImportExportController {

    private final OrganizationResolver orgResolver;
    private final PieceExportService exportService;
    private final PieceImportService importService;

    public PieceImportExportController(
            OrganizationResolver orgResolver,
            PieceExportService exportService,
            PieceImportService importService
    ) {
        this.orgResolver = orgResolver;
        this.exportService = exportService;
        this.importService = importService;
    }

    /**
     * Downloads the organization's pieces (optionally filtered) as a CSV/XLSX file. Readable by any
     * member, including SPECTATOR.
     *
     * @param orgSlug        current organization slug
     * @param format         {@code csv} (default) or {@code xlsx}
     * @param typeId         optional piece-type filter
     * @param locationId     optional location filter
     * @param ownerUserId    optional member-owner filter
     * @param ownerContactId optional contact-owner filter
     * @param status         optional status filter
     * @param q              optional name/description search
     * @return the file as an attachment
     */
    @GetMapping("/export")
    @PreAuthorize("@orgSecurity.canReadOrgContent(#orgSlug, principal)")
    public ResponseEntity<byte[]> export(
            @PathVariable String orgSlug,
            @RequestParam(required = false) String format,
            @RequestParam(required = false) Integer typeId,
            @RequestParam(required = false) Integer locationId,
            @RequestParam(required = false) Integer ownerUserId,
            @RequestParam(required = false) Integer ownerContactId,
            @RequestParam(required = false) PieceStatus status,
            @RequestParam(required = false) String q
    ) {
        Integer orgId = orgResolver.requireCurrent(orgSlug).getId();
        SpreadsheetFormat fmt = SpreadsheetFormat.fromParam(format);
        byte[] body = exportService.export(
                orgId, typeId, locationId, ownerUserId, ownerContactId, status, q, fmt);
        return fileResponse(body, fmt, "pieces-" + orgSlug + "." + fmt.extension());
    }

    /**
     * Downloads an empty file with just the column headers (fixed columns plus one per attribute
     * defined in the organization), as a fill-in template for import. Requires write access.
     *
     * @param orgSlug current organization slug
     * @param format  {@code csv} (default) or {@code xlsx}
     * @return the header-only template file as an attachment
     */
    @GetMapping("/import/template")
    @PreAuthorize("@orgSecurity.canWritePieces(#orgSlug, principal)")
    public ResponseEntity<byte[]> template(
            @PathVariable String orgSlug,
            @RequestParam(required = false) String format
    ) {
        Integer orgId = orgResolver.requireCurrent(orgSlug).getId();
        SpreadsheetFormat fmt = SpreadsheetFormat.fromParam(format);
        byte[] body = exportService.template(orgId, fmt);
        return fileResponse(body, fmt, "pieces-template." + fmt.extension());
    }

    /**
     * Imports pieces from an uploaded CSV/XLSX file. With {@code dryRun=true} the request validates
     * the file and returns a report without writing anything; with {@code dryRun=false} it applies
     * the import in a single all-or-nothing transaction, answering {@code 422} (and writing nothing)
     * when any row is invalid. Requires write access.
     *
     * @param orgSlug current organization slug
     * @param file    the uploaded file (multipart field {@code file})
     * @param format  {@code csv} or {@code xlsx}; inferred from the filename when omitted
     * @param mode    {@code create} (default) or {@code upsert}
     * @param dryRun  validate-only when {@code true} (default {@code false})
     * @return the import report
     */
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@orgSecurity.canWritePieces(#orgSlug, principal)")
    public ResponseEntity<PieceImportReportDto> importPieces(
            @PathVariable String orgSlug,
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String format,
            @RequestParam(required = false) String mode,
            @RequestParam(name = "dryRun", defaultValue = "false") boolean dryRun
    ) {
        Integer orgId = orgResolver.requireCurrent(orgSlug).getId();
        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.PIECES_IMPORT_INVALID_FILE);
        }
        SpreadsheetFormat fmt = resolveFormat(format, file.getOriginalFilename());
        ImportMode importMode = ImportMode.fromParam(mode);
        byte[] content = readBytes(file);
        try {
            PieceImportReportDto report = importService.importPieces(orgId, content, fmt, importMode, dryRun);
            HttpStatus status = (!dryRun && !report.applied())
                    ? HttpStatus.UNPROCESSABLE_CONTENT
                    : HttpStatus.OK;
            return ResponseEntity.status(status).body(report);
        } catch (PieceImportException e) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(e.getReport());
        }
    }

    private static ResponseEntity<byte[]> fileResponse(byte[] body, SpreadsheetFormat fmt, String filename) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType(fmt.contentType()))
                .body(body);
    }

    private static SpreadsheetFormat resolveFormat(String formatParam, String filename) {
        if (formatParam != null && !formatParam.isBlank()) {
            return SpreadsheetFormat.fromParam(formatParam);
        }
        if (filename != null && filename.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            return SpreadsheetFormat.XLSX;
        }
        return SpreadsheetFormat.CSV;
    }

    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No se pudo leer el archivo subido");
        }
    }
}
