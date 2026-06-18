package com.stocka.backend.modules.pieces.importexport.format;

import java.util.List;
import java.util.Map;

/**
 * Format-agnostic in-memory representation of a spreadsheet: an ordered list of column headers and
 * a list of rows, each row mapping a header to its (string) cell value. A missing key means an
 * empty cell.
 *
 * @param headers ordered column headers, as they appear in the first row of the file
 * @param rows    data rows in file order; each map is keyed by the exact header string
 */
public record TabularData(List<String> headers, List<Map<String, String>> rows) {
}
