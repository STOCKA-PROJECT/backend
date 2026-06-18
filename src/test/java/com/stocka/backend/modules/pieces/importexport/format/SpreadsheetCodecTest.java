package com.stocka.backend.modules.pieces.importexport.format;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Format-level coverage of the CSV and XLSX codecs: header/value round-trips, awkward content
 * (commas, quotes, newlines), BOM handling and empty files. These run without a Spring context.
 */
@DisplayName("Spreadsheet codecs")
class SpreadsheetCodecTest {

    private final CsvSpreadsheetCodec csv = new CsvSpreadsheetCodec();
    private final XlsxSpreadsheetCodec xlsx = new XlsxSpreadsheetCodec();

    private static TabularData sample() {
        Map<String, String> row1 = new LinkedHashMap<>();
        row1.put("name", "Hammer");
        row1.put("serial_number", "SN-1");
        row1.put("Tool / Color", "red");
        Map<String, String> row2 = new LinkedHashMap<>();
        row2.put("name", "Anvil");
        row2.put("serial_number", "");
        row2.put("Tool / Color", "grey");
        return new TabularData(List.of("name", "serial_number", "Tool / Color"), List.of(row1, row2));
    }

    private static byte[] write(SpreadsheetCodec codec, TabularData data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        codec.write(data, out);
        return out.toByteArray();
    }

    private static TabularData read(SpreadsheetCodec codec, byte[] bytes) {
        return codec.parse(new ByteArrayInputStream(bytes));
    }

    private static void assertRoundTrip(SpreadsheetCodec codec, TabularData original) {
        TabularData parsed = read(codec, write(codec, original));
        assertThat(parsed.headers()).containsExactlyElementsOf(original.headers());
        assertThat(parsed.rows()).hasSameSizeAs(original.rows());
        for (int i = 0; i < original.rows().size(); i++) {
            Map<String, String> expected = original.rows().get(i);
            Map<String, String> actual = parsed.rows().get(i);
            for (String header : original.headers()) {
                assertThat(actual.getOrDefault(header, ""))
                        .as("row %d, column '%s'", i, header)
                        .isEqualTo(expected.getOrDefault(header, ""));
            }
        }
    }

    @Nested
    @DisplayName("CSV")
    class Csv {

        @Test
        @DisplayName("round-trips headers and values")
        void roundTrip() {
            assertRoundTrip(csv, sample());
        }

        @Test
        @DisplayName("preserves commas, quotes and newlines inside a cell")
        void roundTrip_awkwardContent() {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("name", "A, B \"quoted\"");
            row.put("description", "line1\nline2");
            TabularData data = new TabularData(List.of("name", "description"), List.of(row));
            assertRoundTrip(csv, data);
        }

        @Test
        @DisplayName("writes a UTF-8 BOM and strips it back on parse")
        void bom_writtenAndStripped() {
            byte[] bytes = write(csv, sample());
            assertThat(bytes[0] & 0xFF).isEqualTo(0xEF);
            assertThat(bytes[1] & 0xFF).isEqualTo(0xBB);
            assertThat(bytes[2] & 0xFF).isEqualTo(0xBF);
            TabularData parsed = read(csv, bytes);
            assertThat(parsed.headers().get(0)).isEqualTo("name");
        }

        @Test
        @DisplayName("parses a file with only headers as zero rows")
        void headersOnly() {
            TabularData data = new TabularData(List.of("name", "serial_number"), List.of());
            TabularData parsed = read(csv, write(csv, data));
            assertThat(parsed.headers()).containsExactly("name", "serial_number");
            assertThat(parsed.rows()).isEmpty();
        }

        @Test
        @DisplayName("parses an empty stream as empty data")
        void emptyStream() {
            TabularData parsed = read(csv, new byte[0]);
            assertThat(parsed.headers()).isEmpty();
            assertThat(parsed.rows()).isEmpty();
        }

        @Test
        @DisplayName("preserves accented characters via UTF-8")
        void accents() {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("location_path", "Almacén/Estantería");
            TabularData data = new TabularData(List.of("location_path"), List.of(row));
            TabularData parsed = read(csv, write(csv, data));
            assertThat(parsed.rows().get(0).get("location_path")).isEqualTo("Almacén/Estantería");
        }
    }

    @Nested
    @DisplayName("XLSX")
    class Xlsx {

        @Test
        @DisplayName("round-trips headers and values")
        void roundTrip() {
            assertRoundTrip(xlsx, sample());
        }

        @Test
        @DisplayName("preserves numeric-looking strings without adding decimals")
        void numericLikeStrings() {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("serial_number", "123");
            TabularData data = new TabularData(List.of("serial_number"), List.of(row));
            TabularData parsed = read(xlsx, write(xlsx, data));
            assertThat(parsed.rows().get(0).get("serial_number")).isEqualTo("123");
        }

        @Test
        @DisplayName("parses a file with only headers as zero rows")
        void headersOnly() {
            TabularData data = new TabularData(List.of("name", "serial_number"), List.of());
            TabularData parsed = read(xlsx, write(xlsx, data));
            assertThat(parsed.headers()).containsExactly("name", "serial_number");
            assertThat(parsed.rows()).isEmpty();
        }
    }

    @Test
    @DisplayName("a CSV export can be parsed by the CSV codec across both content types")
    void csvBytesAreUtf8() {
        byte[] bytes = write(csv, sample());
        String asString = new String(bytes, StandardCharsets.UTF_8);
        assertThat(asString).contains("Tool / Color").contains("Hammer");
    }
}
