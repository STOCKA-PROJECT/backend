package com.stocka.backend.modules.pieces.importexport.format;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import com.stocka.backend.modules.pieces.importexport.dto.SpreadsheetFormat;

/**
 * CSV codec backed by Apache Commons CSV. Reads/writes UTF-8; writes a leading byte-order mark so
 * Excel detects the encoding and accented characters render correctly. The first record is treated
 * as the header row, which sidesteps Commons CSV's duplicate-header handling and BOM quirks.
 */
@Component
public class CsvSpreadsheetCodec implements SpreadsheetCodec {

    private static final char BOM = '﻿';

    @Override
    public SpreadsheetFormat format() {
        return SpreadsheetFormat.CSV;
    }

    @Override
    public TabularData parse(InputStream in) {
        Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
        try (CSVParser parser = CSVParser.parse(reader, CSVFormat.DEFAULT)) {
            Iterator<CSVRecord> it = parser.iterator();
            if (!it.hasNext()) {
                return new TabularData(List.of(), List.of());
            }
            List<String> headers = new ArrayList<>();
            for (String h : it.next()) {
                headers.add(h);
            }
            if (!headers.isEmpty()) {
                headers.set(0, stripBom(headers.get(0)));
            }
            List<Map<String, String>> rows = new ArrayList<>();
            while (it.hasNext()) {
                CSVRecord record = it.next();
                Map<String, String> row = new LinkedHashMap<>();
                for (int i = 0; i < headers.size(); i++) {
                    row.put(headers.get(i), i < record.size() ? record.get(i) : "");
                }
                rows.add(row);
            }
            return new TabularData(headers, rows);
        } catch (IOException | IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No se pudo leer el archivo CSV: " + e.getMessage());
        }
    }

    @Override
    public void write(TabularData data, OutputStream out) {
        // Deliberately not wrapped in try-with-resources: closing a CSVPrinter closes the
        // underlying writer and stream, but the contract is to flush — not close — the caller's
        // stream. The CSVPrinter holds no resource beyond the writer, so flushing is enough.
        try {
            Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
            writer.write(BOM);
            CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT);
            printer.printRecord(data.headers());
            for (Map<String, String> row : data.rows()) {
                List<String> cells = new ArrayList<>(data.headers().size());
                for (String header : data.headers()) {
                    cells.add(row.getOrDefault(header, ""));
                }
                printer.printRecord(cells);
            }
            printer.flush();
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo escribir el CSV", e);
        }
    }

    private static String stripBom(String value) {
        if (value != null && !value.isEmpty() && value.charAt(0) == BOM) {
            return value.substring(1);
        }
        return value;
    }
}
