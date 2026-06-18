package com.stocka.backend.modules.pieces.importexport.format;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import org.springframework.stereotype.Component;

import com.stocka.backend.modules.pieces.importexport.dto.SpreadsheetFormat;

/**
 * Thin convenience layer over {@link SpreadsheetCodecFactory} that converts between in-memory
 * {@code byte[]} payloads and {@link TabularData}, so callers do not deal with streams directly.
 */
@Component
public class SpreadsheetSerializer {

    private final SpreadsheetCodecFactory codecs;

    public SpreadsheetSerializer(SpreadsheetCodecFactory codecs) {
        this.codecs = codecs;
    }

    /**
     * Serializes tabular data to a file payload in the requested format.
     *
     * @param data   the headers and rows to serialize
     * @param format target format
     * @return the serialized bytes
     */
    public byte[] serialize(TabularData data, SpreadsheetFormat format) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        codecs.forFormat(format).write(data, out);
        return out.toByteArray();
    }

    /**
     * Parses a file payload in the given format into tabular data.
     *
     * @param content the uploaded file bytes
     * @param format  the format to parse as
     * @return the parsed headers and rows
     * @throws org.springframework.web.server.ResponseStatusException 400 when the bytes are not
     *                                                                valid for {@code format}
     */
    public TabularData parse(byte[] content, SpreadsheetFormat format) {
        return codecs.forFormat(format).parse(new ByteArrayInputStream(content));
    }
}
