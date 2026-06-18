package com.stocka.backend.modules.pieces.importexport.format;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import com.stocka.backend.modules.pieces.importexport.dto.SpreadsheetFormat;

/**
 * XLSX codec backed by Apache POI. The first sheet's first row is the header row. Cells are read as
 * their displayed string via {@link DataFormatter}; rows are written as string cells so an
 * export → edit → import round-trip preserves values verbatim. Writing uses the streaming
 * {@link SXSSFWorkbook} so large exports keep a small memory footprint.
 *
 * <p>Date/datetime attributes should be kept as ISO-8601 text in the file: a cell that Excel
 * stores as a native date renders in the workbook's locale, which the attribute validators may not
 * accept.
 */
@Component
public class XlsxSpreadsheetCodec implements SpreadsheetCodec {

    private static final String SHEET_NAME = "pieces";

    @Override
    public SpreadsheetFormat format() {
        return SpreadsheetFormat.XLSX;
    }

    @Override
    public TabularData parse(InputStream in) {
        try (Workbook workbook = new XSSFWorkbook(in)) {
            if (workbook.getNumberOfSheets() == 0) {
                return new TabularData(List.of(), List.of());
            }
            Sheet sheet = workbook.getSheetAt(0);
            int firstRowNum = sheet.getFirstRowNum();
            Row headerRow = sheet.getRow(firstRowNum);
            if (headerRow == null) {
                return new TabularData(List.of(), List.of());
            }
            DataFormatter formatter = new DataFormatter();
            List<String> headers = new ArrayList<>();
            int columnCount = headerRow.getLastCellNum();
            for (int c = 0; c < columnCount; c++) {
                Cell cell = headerRow.getCell(c);
                headers.add(cell == null ? "" : formatter.formatCellValue(cell).trim());
            }
            List<Map<String, String>> rows = new ArrayList<>();
            for (int r = firstRowNum + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                Map<String, String> values = new LinkedHashMap<>();
                for (int c = 0; c < headers.size(); c++) {
                    Cell cell = row == null ? null : row.getCell(c);
                    values.put(headers.get(c), cell == null ? "" : formatter.formatCellValue(cell));
                }
                rows.add(values);
            }
            return new TabularData(headers, rows);
        } catch (IOException | RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No se pudo leer el archivo Excel: " + e.getMessage());
        }
    }

    @Override
    public void write(TabularData data, OutputStream out) {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook()) {
            Sheet sheet = workbook.createSheet(SHEET_NAME);
            Row headerRow = sheet.createRow(0);
            List<String> headers = data.headers();
            for (int c = 0; c < headers.size(); c++) {
                headerRow.createCell(c).setCellValue(headers.get(c));
            }
            int r = 1;
            for (Map<String, String> row : data.rows()) {
                Row xlsxRow = sheet.createRow(r++);
                for (int c = 0; c < headers.size(); c++) {
                    xlsxRow.createCell(c).setCellValue(row.getOrDefault(headers.get(c), ""));
                }
            }
            workbook.write(out);
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo escribir el Excel", e);
        }
    }
}
