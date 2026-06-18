package com.stocka.backend.modules.pieces.importexport.format;

import java.io.InputStream;
import java.io.OutputStream;

import com.stocka.backend.modules.pieces.importexport.dto.SpreadsheetFormat;

/**
 * Reads and writes {@link TabularData} in one concrete on-disk format. Implementations isolate all
 * CSV/XLSX-specific code so the import/export services stay format-agnostic.
 */
public interface SpreadsheetCodec {

    /**
     * @return the format this codec handles
     */
    SpreadsheetFormat format();

    /**
     * Parses an uploaded file into tabular data. The first row is treated as the header row.
     *
     * @param in the file bytes; the caller is responsible for closing the underlying stream
     * @return the parsed headers and rows
     * @throws org.springframework.web.server.ResponseStatusException 400 when the file cannot be
     *                                                                parsed as this format
     */
    TabularData parse(InputStream in);

    /**
     * Serializes tabular data to the target stream.
     *
     * @param data the headers and rows to write
     * @param out  the destination stream; flushed but not closed by this method
     */
    void write(TabularData data, OutputStream out);
}
