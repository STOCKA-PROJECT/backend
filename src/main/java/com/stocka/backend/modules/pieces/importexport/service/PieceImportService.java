package com.stocka.backend.modules.pieces.importexport.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.stocka.backend.modules.common.error.ApiException;
import com.stocka.backend.modules.common.error.ErrorCodes;
import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.organizations.service.OrganizationService;
import com.stocka.backend.modules.pieces.dto.AttributeValueInputDto;
import com.stocka.backend.modules.pieces.dto.CreatePieceDto;
import com.stocka.backend.modules.pieces.dto.UpdatePieceDto;
import com.stocka.backend.modules.pieces.entity.Piece;
import com.stocka.backend.modules.pieces.importexport.config.PieceImportExportProperties;
import com.stocka.backend.modules.pieces.importexport.dto.ImportMode;
import com.stocka.backend.modules.pieces.importexport.dto.PieceImportReportDto;
import com.stocka.backend.modules.pieces.importexport.dto.RowAction;
import com.stocka.backend.modules.pieces.importexport.dto.RowResultDto;
import com.stocka.backend.modules.pieces.importexport.dto.SpreadsheetFormat;
import com.stocka.backend.modules.pieces.importexport.format.SpreadsheetSerializer;
import com.stocka.backend.modules.pieces.importexport.format.TabularData;
import com.stocka.backend.modules.pieces.importexport.service.RowReferenceResolver.AttributeColumnIndex;
import com.stocka.backend.modules.pieces.importexport.service.RowReferenceResolver.ResolvedAttributeColumn;
import com.stocka.backend.modules.pieces.repository.PieceRepository;
import com.stocka.backend.modules.piecetypes.entity.AttributeType;

/**
 * Bulk create/update of pieces from a CSV/XLSX file, with a two-step dry-run → commit flow.
 *
 * <p>Validation reuses the real {@link com.stocka.backend.modules.pieces.service.PieceService}
 * create/update logic: in dry-run every row is executed inside a nested {@code REQUIRES_NEW}
 * transaction that is always rolled back, so the report reflects exactly what a commit would do
 * (lifecycle notifications never fire because they are {@code AFTER_COMMIT}). The commit re-runs the
 * full validation first and only persists when there are zero blocking errors, applying every row
 * in a single transaction (all-or-nothing).
 *
 * <p>Blank cells mean "not provided": the import never clears or detaches a field. Owner/location/
 * type and {@code MEMBER} references are given by name/e-mail and resolved by
 * {@link RowReferenceResolver}.
 */
@Service
public class PieceImportService {

    private final OrganizationService organizationService;
    private final com.stocka.backend.modules.pieces.service.PieceService pieceService;
    private final PieceRepository pieceRepository;
    private final RowReferenceResolver referenceResolver;
    private final SpreadsheetSerializer serializer;
    private final PieceImportExportProperties properties;
    private final MessageSource messageSource;
    /** {@code REQUIRES_NEW}, always rolled back: used to validate a row by really running it. */
    private final TransactionTemplate rollbackTemplate;
    /** Default {@code REQUIRED}: the single all-or-nothing commit transaction. */
    private final TransactionTemplate writeTemplate;

    public PieceImportService(
            OrganizationService organizationService,
            com.stocka.backend.modules.pieces.service.PieceService pieceService,
            PieceRepository pieceRepository,
            RowReferenceResolver referenceResolver,
            SpreadsheetSerializer serializer,
            PieceImportExportProperties properties,
            MessageSource messageSource,
            PlatformTransactionManager transactionManager
    ) {
        this.organizationService = organizationService;
        this.pieceService = pieceService;
        this.pieceRepository = pieceRepository;
        this.referenceResolver = referenceResolver;
        this.serializer = serializer;
        this.properties = properties;
        this.messageSource = messageSource;
        this.rollbackTemplate = new TransactionTemplate(transactionManager);
        this.rollbackTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.writeTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Imports pieces from an uploaded file.
     *
     * @param orgId   organization id
     * @param content raw file bytes
     * @param format  file format
     * @param mode    create-only or upsert
     * @param dryRun  when {@code true}, validate only and write nothing
     * @return the import report (per-row outcomes and aggregate counters)
     * @throws ApiException 400 ({@code pieces.import.invalid_file}) when the file has no header row,
     *                      or 422 ({@code pieces.import.too_many_rows}) when it exceeds the row cap
     * @throws PieceImportException when a commit is rejected so the transaction rolls back
     */
    public PieceImportReportDto importPieces(Integer orgId, byte[] content, SpreadsheetFormat format,
                                             ImportMode mode, boolean dryRun) {
        Organization org = organizationService.findById(orgId);
        TabularData data = serializer.parse(content, format);
        if (data.headers().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.PIECES_IMPORT_INVALID_FILE);
        }
        int cap = properties.getMaxImportRows();
        if (data.rows().size() > cap) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                    ErrorCodes.PIECES_IMPORT_TOO_MANY_ROWS, Map.of("max", cap));
        }

        AttributeColumnIndex attrIndex = referenceResolver.buildAttributeIndex(org);
        List<String> warnings = unknownColumnWarnings(data.headers(), attrIndex);

        List<RowResultDto> previewRows = validateAll(orgId, org, data, mode, attrIndex);
        PieceImportReportDto preview = PieceImportReportDto.of(dryRun, false, mode, previewRows, warnings);
        if (dryRun || preview.hasErrors()) {
            return preview;
        }
        return writeAll(orgId, org, data, mode, attrIndex, warnings);
    }

    // ---------- validation pass (no writes) ----------

    private List<RowResultDto> validateAll(Integer orgId, Organization org, TabularData data,
                                           ImportMode mode, AttributeColumnIndex attrIndex) {
        Map<String, String> fixedHeaders = mapFixedHeaders(data.headers());
        Set<String> seenSerials = new HashSet<>();
        List<RowResultDto> results = new ArrayList<>();
        int rowNumber = 0;
        for (Map<String, String> rowMap : data.rows()) {
            rowNumber++;
            String serial = blankToNull(cell(fixedHeaders, rowMap, PieceColumns.SERIAL_NUMBER));
            String name = trimToNull(cell(fixedHeaders, rowMap, PieceColumns.NAME));
            try {
                RowPlan plan = planRow(orgId, org, data.headers(), rowMap, fixedHeaders, mode, attrIndex);
                if (plan.action() == RowAction.SKIP) {
                    results.add(RowResultDto.ok(rowNumber, RowAction.SKIP, null, serial, name));
                    continue;
                }
                if (serial != null && !seenSerials.add(serial)) {
                    results.add(duplicateSerial(rowNumber, serial, name));
                    continue;
                }
                executeInRollback(orgId, plan);
                results.add(RowResultDto.ok(rowNumber, plan.action(), null, serial, name));
            } catch (RowValidationException e) {
                results.add(RowResultDto.error(rowNumber, serial, name, e.getMessages()));
            } catch (RuntimeException e) {
                results.add(RowResultDto.error(rowNumber, serial, name, List.of(messageOf(e))));
            }
        }
        return results;
    }

    /**
     * Runs the planned create/update inside a fresh transaction that is always rolled back, so the
     * real validation fires without persisting anything.
     */
    private void executeInRollback(Integer orgId, RowPlan plan) {
        rollbackTemplate.executeWithoutResult(status -> {
            if (plan.action() == RowAction.CREATE) {
                pieceService.create(orgId, plan.createDto());
            } else {
                pieceService.update(orgId, plan.existingPieceId(), plan.updateDto());
            }
            status.setRollbackOnly();
        });
    }

    // ---------- commit pass (single transaction, all-or-nothing) ----------

    /**
     * Persists every row in a single transaction. Already-validated input means the catch blocks
     * are a safety net against races (e.g. a concurrent import grabbing the same serial); hitting
     * one rolls the whole transaction back via {@link PieceImportException}.
     */
    private PieceImportReportDto writeAll(Integer orgId, Organization org, TabularData data,
                                          ImportMode mode, AttributeColumnIndex attrIndex,
                                          List<String> warnings) {
        return writeTemplate.execute(status -> {
            Map<String, String> fixedHeaders = mapFixedHeaders(data.headers());
            Set<String> seenSerials = new HashSet<>();
            List<RowResultDto> results = new ArrayList<>();
            int rowNumber = 0;
            for (Map<String, String> rowMap : data.rows()) {
                rowNumber++;
                String serial = blankToNull(cell(fixedHeaders, rowMap, PieceColumns.SERIAL_NUMBER));
                String name = trimToNull(cell(fixedHeaders, rowMap, PieceColumns.NAME));
                try {
                    RowPlan plan = planRow(orgId, org, data.headers(), rowMap, fixedHeaders, mode, attrIndex);
                    if (plan.action() == RowAction.SKIP) {
                        results.add(RowResultDto.ok(rowNumber, RowAction.SKIP, null, serial, name));
                        continue;
                    }
                    if (serial != null && !seenSerials.add(serial)) {
                        results.add(duplicateSerial(rowNumber, serial, name));
                        throw new PieceImportException(abortReport(mode, results, warnings));
                    }
                    Integer pieceId = plan.action() == RowAction.CREATE
                            ? pieceService.create(orgId, plan.createDto()).getId()
                            : pieceService.update(orgId, plan.existingPieceId(), plan.updateDto()).getId();
                    results.add(RowResultDto.ok(rowNumber, plan.action(), pieceId, serial, name));
                } catch (PieceImportException e) {
                    throw e;
                } catch (RowValidationException e) {
                    results.add(RowResultDto.error(rowNumber, serial, name, e.getMessages()));
                    throw new PieceImportException(abortReport(mode, results, warnings));
                } catch (RuntimeException e) {
                    results.add(RowResultDto.error(rowNumber, serial, name, List.of(messageOf(e))));
                    throw new PieceImportException(abortReport(mode, results, warnings));
                }
            }
            return PieceImportReportDto.of(false, true, mode, results, warnings);
        });
    }

    private PieceImportReportDto abortReport(ImportMode mode, List<RowResultDto> results,
                                             List<String> warnings) {
        List<String> all = new ArrayList<>(warnings);
        all.add("La importación se canceló por errores; no se aplicó ningún cambio.");
        return PieceImportReportDto.of(false, false, mode, results, all);
    }

    // ---------- per-row planning (shared) ----------

    /**
     * Resolves a single row into the create/update command to run. Throws {@link
     * RowValidationException} for any unresolved reference (owner, location, type, member).
     */
    private RowPlan planRow(Integer orgId, Organization org, List<String> headers,
                            Map<String, String> rowMap, Map<String, String> fixedHeaders,
                            ImportMode mode, AttributeColumnIndex attrIndex) {
        if (isRowBlank(rowMap)) {
            return RowPlan.skip();
        }
        String name = cell(fixedHeaders, rowMap, PieceColumns.NAME).trim();
        String serial = blankToNull(cell(fixedHeaders, rowMap, PieceColumns.SERIAL_NUMBER));
        String description = cell(fixedHeaders, rowMap, PieceColumns.DESCRIPTION);

        String ownerEmailCell = cell(fixedHeaders, rowMap, PieceColumns.OWNER_EMAIL);
        String ownerContactCell = cell(fixedHeaders, rowMap, PieceColumns.OWNER_CONTACT);
        if (!ownerEmailCell.isBlank() && !ownerContactCell.isBlank()) {
            throw new RowValidationException(
                    "Indica solo una de las columnas owner_email / owner_contact, no ambas");
        }
        Integer ownerUserId = referenceResolver.resolveOwnerUserId(org, ownerEmailCell);
        Integer ownerContactId = referenceResolver.resolveOwnerContactId(org, ownerContactCell);
        Integer locationId = referenceResolver.resolveLocationId(org,
                cell(fixedHeaders, rowMap, PieceColumns.LOCATION_PATH));
        List<Integer> typeIds = referenceResolver.resolveTypeIds(org,
                cell(fixedHeaders, rowMap, PieceColumns.PIECE_TYPES));
        List<AttributeValueInputDto> attributeValues = buildAttributeValues(headers, rowMap, attrIndex);

        Integer existingId = null;
        if (mode == ImportMode.UPSERT && serial != null) {
            existingId = pieceRepository.findFirstByOrganization_IdAndSerialNumber(orgId, serial)
                    .map(Piece::getId)
                    .orElse(null);
        }

        if (existingId != null) {
            UpdatePieceDto dto = new UpdatePieceDto();
            if (!name.isEmpty()) {
                dto.setName(name);
            }
            if (!description.isBlank()) {
                dto.setDescription(description);
            }
            if (!typeIds.isEmpty()) {
                dto.setPieceTypeIds(typeIds);
            }
            if (ownerUserId != null) {
                dto.setOwnerUserId(ownerUserId);
            }
            if (ownerContactId != null) {
                dto.setOwnerContactId(ownerContactId);
            }
            if (locationId != null) {
                dto.setLocationId(locationId);
            }
            if (!attributeValues.isEmpty()) {
                dto.setAttributeValues(attributeValues);
            }
            return RowPlan.update(existingId, dto);
        }

        CreatePieceDto dto = new CreatePieceDto()
                .setName(name)
                .setSerialNumber(serial)
                .setDescription(description)
                .setPieceTypeIds(typeIds.isEmpty() ? null : typeIds)
                .setOwnerUserId(ownerUserId)
                .setOwnerContactId(ownerContactId)
                .setLocationId(locationId)
                .setAttributeValues(attributeValues.isEmpty() ? null : attributeValues);
        return RowPlan.create(dto);
    }

    private List<AttributeValueInputDto> buildAttributeValues(List<String> headers,
                                                              Map<String, String> rowMap,
                                                              AttributeColumnIndex attrIndex) {
        List<AttributeValueInputDto> values = new ArrayList<>();
        for (String header : headers) {
            if (PieceColumns.isFixed(header)) {
                continue;
            }
            ResolvedAttributeColumn column = referenceResolver.matchAttributeColumn(attrIndex, header);
            if (column == null) {
                continue;
            }
            String raw = rowMap.get(header);
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String value = column.type() == AttributeType.MEMBER
                    ? String.valueOf(referenceResolver.resolveUserIdByEmail(raw))
                    : raw;
            values.add(new AttributeValueInputDto(column.attributeId(), column.scope(), value));
        }
        return values;
    }

    private List<String> unknownColumnWarnings(List<String> headers, AttributeColumnIndex attrIndex) {
        List<String> warnings = new ArrayList<>();
        for (String header : headers) {
            if (header == null || header.isBlank() || PieceColumns.isFixed(header)) {
                continue;
            }
            if (referenceResolver.matchAttributeColumn(attrIndex, header) == null) {
                warnings.add("Columna desconocida ignorada: '" + header + "'");
            }
        }
        return warnings;
    }

    // ---------- helpers ----------

    private static Map<String, String> mapFixedHeaders(List<String> headers) {
        Map<String, String> map = new HashMap<>();
        for (String fixed : PieceColumns.FIXED_COLUMNS) {
            String normalizedFixed = PieceColumns.normalize(fixed);
            for (String header : headers) {
                if (PieceColumns.normalize(header).equals(normalizedFixed)) {
                    map.put(fixed, header);
                    break;
                }
            }
        }
        return map;
    }

    private static String cell(Map<String, String> fixedHeaders, Map<String, String> rowMap,
                               String column) {
        String header = fixedHeaders.get(column);
        if (header == null) {
            return "";
        }
        String value = rowMap.get(header);
        return value == null ? "" : value;
    }

    private static boolean isRowBlank(Map<String, String> rowMap) {
        return rowMap.values().stream().allMatch(v -> v == null || v.isBlank());
    }

    private static RowResultDto duplicateSerial(int rowNumber, String serial, String name) {
        return RowResultDto.error(rowNumber, serial, name,
                List.of("Número de serie duplicado en el archivo: '" + serial + "'"));
    }

    private String messageOf(RuntimeException e) {
        if (e instanceof ResponseStatusException rse) {
            return rse.getReason() != null ? rse.getReason() : rse.getStatusCode().toString();
        }
        if (e instanceof ApiException ae) {
            try {
                return messageSource.getMessage("errors." + ae.getCode(), null,
                        LocaleContextHolder.getLocale());
            } catch (NoSuchMessageException ex) {
                return ae.getCode();
            }
        }
        return e.getMessage() == null ? "Error de validación" : e.getMessage();
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String trimToNull(String value) {
        return blankToNull(value);
    }

    /**
     * The resolved command for a single row.
     *
     * @param action          what to do
     * @param existingPieceId target piece id for {@link RowAction#UPDATE}; {@code null} otherwise
     * @param createDto       payload for {@link RowAction#CREATE}; {@code null} otherwise
     * @param updateDto       payload for {@link RowAction#UPDATE}; {@code null} otherwise
     */
    private record RowPlan(RowAction action, Integer existingPieceId, CreatePieceDto createDto,
                           UpdatePieceDto updateDto) {

        static RowPlan skip() {
            return new RowPlan(RowAction.SKIP, null, null, null);
        }

        static RowPlan create(CreatePieceDto dto) {
            return new RowPlan(RowAction.CREATE, null, dto, null);
        }

        static RowPlan update(Integer pieceId, UpdatePieceDto dto) {
            return new RowPlan(RowAction.UPDATE, pieceId, null, dto);
        }
    }
}
