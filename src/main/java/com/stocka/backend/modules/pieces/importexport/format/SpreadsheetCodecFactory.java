package com.stocka.backend.modules.pieces.importexport.format;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.stocka.backend.modules.pieces.importexport.dto.SpreadsheetFormat;

/**
 * Resolves the {@link SpreadsheetCodec} matching a {@link SpreadsheetFormat}. Spring injects every
 * codec bean and we index them once at startup.
 */
@Component
public class SpreadsheetCodecFactory {

    private final Map<SpreadsheetFormat, SpreadsheetCodec> byFormat = new EnumMap<>(SpreadsheetFormat.class);

    public SpreadsheetCodecFactory(List<SpreadsheetCodec> codecs) {
        for (SpreadsheetCodec codec : codecs) {
            byFormat.put(codec.format(), codec);
        }
    }

    /**
     * @param format the desired format
     * @return the codec able to read/write {@code format}
     * @throws IllegalStateException when no codec is registered for {@code format} (a wiring bug)
     */
    public SpreadsheetCodec forFormat(SpreadsheetFormat format) {
        SpreadsheetCodec codec = byFormat.get(format);
        if (codec == null) {
            throw new IllegalStateException("No hay codec registrado para el formato " + format);
        }
        return codec;
    }
}
