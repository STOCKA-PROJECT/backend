package com.stocka.backend.modules.mcp.tool;

import com.stocka.backend.modules.common.schema.Param;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.stocka.backend.modules.mcp.tool.support.OrgAccess;
import com.stocka.backend.modules.oauth.OAuthScope;
import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.pieces.dto.PieceFilterCriteria;
import com.stocka.backend.modules.pieces.entity.PieceStatus;
import com.stocka.backend.modules.pieces.importexport.dto.ImportMode;
import com.stocka.backend.modules.pieces.importexport.dto.PieceImportReportDto;
import com.stocka.backend.modules.pieces.importexport.dto.SpreadsheetFormat;
import com.stocka.backend.modules.pieces.importexport.service.PieceExportService;
import com.stocka.backend.modules.pieces.importexport.service.PieceImportService;
import com.stocka.backend.modules.pieces.service.PieceFilterParamParser;

/**
 * Bulk work on pieces through the CSV schema shared with the dashboard's import/export.
 */
@Component
public class ImportExportTools implements ToolSource {

    public record ExportInput(
            @Param(value = "Organization slug", required = true) String org,
            @Param("Free text filter") String q,
            @Param("Only these piece type ids") List<Integer> typeIds,
            @Param("Only this location id") Integer locationId,
            @Param("Only this owner user id") Integer ownerUserId,
            @Param("Only this owner contact id") Integer ownerContactId,
            @Param("PENDING or ACTIVE") PieceStatus status,
            @Param("Attribute value filters") List<PieceTools.AttributeFilter> attributes) {
    }

    public record ImportInput(
            @Param(value = "Organization slug", required = true) String org,
            @Param(value = "CSV text with the same columns as pieces_export / pieces_import_template", required = true) String csv,
            @Param("CREATE (default) or UPSERT (match existing pieces by serial number)") ImportMode mode,
            @Param("Validate only and return the per-row report without writing (default true). Run with false only after showing the user the dry-run report") Boolean dryRun) {
    }

    public record OrgInput(@Param(value = "Organization slug", required = true) String org) {
    }

    private final OrgAccess access;
    private final PieceExportService exports;
    private final PieceImportService imports;
    private final PieceFilterParamParser filters;

    public ImportExportTools(OrgAccess access, PieceExportService exports, PieceImportService imports,
                             PieceFilterParamParser filters) {
        this.access = access;
        this.exports = exports;
        this.imports = imports;
        this.filters = filters;
    }

    @Override
    public List<ToolDefinition<?>> tools() {
        return List.of(
                ToolDefinition.of("pieces_export",
                        "Exports pieces as CSV text (fixed columns plus one column per attribute: 'Type / Attribute' "
                                + "or 'Org / Attribute'; references by name: owner_email, location_path with '/', "
                                + "piece_types separated by ';'). Same filters as pieces_search.",
                        ExportInput.class, OAuthScope.READ, ToolHints.READ, this::export),
                ToolDefinition.of("pieces_import_template",
                        "Empty CSV with the header row the import expects for this organization.",
                        OrgInput.class, OAuthScope.READ, ToolHints.READ, this::template),
                ToolDefinition.of("pieces_import",
                        "Imports pieces from CSV text. dryRun=true (default) validates and reports per row without "
                                + "writing; dryRun=false applies all rows or none. Blank cells leave fields unchanged. "
                                + "Needs OWNER, MANAGER or USER.",
                        ImportInput.class, OAuthScope.INVENTORY_WRITE, ToolHints.CREATE, this::importCsv)
                        .returning(PieceImportReportDto.class));
    }

    private ToolResults.Text export(ToolCall<ExportInput> call) {
        ExportInput in = call.input();
        Organization org = access.read(call.caller(), in.org());
        List<String> attr = null;
        if (in.attributes() != null && !in.attributes().isEmpty()) {
            attr = new ArrayList<>();
            for (PieceTools.AttributeFilter f : in.attributes()) {
                attr.add(PieceTools.encodeFilter(f));
            }
        }
        PieceFilterCriteria criteria = filters.parse(null, in.typeIds(), in.locationId(), in.ownerUserId(),
                in.ownerContactId(), in.status(), in.q(), attr);
        return new ToolResults.Text(text(exports.export(org.getId(), criteria, SpreadsheetFormat.CSV)));
    }

    private ToolResults.Text template(ToolCall<OrgInput> call) {
        Organization org = access.read(call.caller(), call.input().org());
        return new ToolResults.Text(text(exports.template(org.getId(), SpreadsheetFormat.CSV)));
    }

    private PieceImportReportDto importCsv(ToolCall<ImportInput> call) {
        ImportInput in = call.input();
        Organization org = access.writePieces(call.caller(), in.org());
        boolean dryRun = in.dryRun() == null || in.dryRun();
        ImportMode mode = in.mode() == null ? ImportMode.CREATE : in.mode();
        byte[] bytes = (in.csv() == null ? "" : in.csv()).getBytes(StandardCharsets.UTF_8);
        return imports.importPieces(org.getId(), bytes, SpreadsheetFormat.CSV, mode, dryRun);
    }

    static String text(byte[] bytes) {
        String s = new String(bytes, StandardCharsets.UTF_8);
        return s.startsWith("﻿") ? s.substring(1) : s;
    }
}
