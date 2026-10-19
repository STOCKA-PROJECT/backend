package com.stocka.backend.modules.timelines.show;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Deterministic JSON for show packages, matching the editor's {@code utils/canonicalJson.ts} and
 * the player's {@code tools/show_hash.py} so every side computes the same hash: object keys sorted
 * recursively, no whitespace, integral floats written as integers ({@code 10}, never {@code 10.0}),
 * UTF-8 without escaping non-ASCII characters. {@link #pretty} writes the same tree the way
 * {@code JSON.stringify(value, null, 2)} does, for the files that travel to the device.
 */
public final class ShowCanonicalJson {

    /** Keys of the package the hash covers, in the order the hash input lists them. */
    public static final List<String> HASHED_KEYS = List.of("board", "mode", "hardware", "timeline", "durationMs", "assets");

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String INDENT = "  ";

    private ShowCanonicalJson() {
    }

    /**
     * @param value any Jackson-serializable value (records, maps, lists, JSON nodes)
     * @return its canonical JSON (keys sorted, compact)
     */
    public static String canonical(Object value) {
        StringBuilder out = new StringBuilder();
        write(JSON.valueToTree(value), out, true, false, 0);
        return out.toString();
    }

    /**
     * @param value any Jackson-serializable value
     * @return its JSON indented with two spaces, keys in insertion order, as {@code JSON.stringify} writes it
     */
    public static String pretty(Object value) {
        StringBuilder out = new StringBuilder();
        write(JSON.valueToTree(value), out, false, true, 0);
        return out.toString();
    }

    /**
     * The part of a package the hash covers: everything but {@code meta}, {@code format} and
     * {@code version}.
     *
     * @param pkg a package (or any object with those keys), typically a {@link ShowPackage}
     * @return the hashed subset, in {@link #HASHED_KEYS} order
     */
    public static Map<String, Object> hashInput(Object pkg) {
        JsonNode tree = JSON.valueToTree(pkg);
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : HASHED_KEYS) {
            if (tree.has(key)) {
                out.put(key, tree.get(key));
            }
        }
        return out;
    }

    /**
     * @param pkg a package (meta ignored)
     * @return {@code sha256:} + hex digest of the canonical JSON of {@link #hashInput}
     */
    public static String showHash(Object pkg) {
        return "sha256:" + sha256Hex(canonical(hashInput(pkg)));
    }

    /**
     * @param text any text
     * @return lowercase hex SHA-256 of its UTF-8 bytes
     */
    public static String sha256Hex(String text) {
        return sha256Hex(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @param bytes any bytes
     * @return lowercase hex SHA-256
     */
    public static String sha256Hex(byte[] bytes) {
        return HexFormat.of().formatHex(sha256().digest(bytes));
    }

    /** @return a fresh SHA-256 digest */
    public static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * @param node   tree to write
     * @param out    target
     * @param sorted whether object keys are sorted (canonical) or kept (pretty)
     * @param pretty whether to indent (two spaces, one element per line) or write compactly
     * @param depth  current indentation level
     */
    private static void write(JsonNode node, StringBuilder out, boolean sorted, boolean pretty, int depth) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            out.append("null");
        } else if (node.isBoolean()) {
            out.append(node.booleanValue());
        } else if (node.isNumber()) {
            out.append(ShowNumbers.toJsString(node.numberValue()));
        } else if (node.isTextual()) {
            writeString(node.asText(), out);
        } else if (node.isArray()) {
            if (node.isEmpty()) {
                out.append("[]");
                return;
            }
            out.append('[');
            boolean first = true;
            for (JsonNode child : node) {
                if (!first) out.append(',');
                first = false;
                newline(out, pretty, depth + 1);
                write(child, out, sorted, pretty, depth + 1);
            }
            newline(out, pretty, depth);
            out.append(']');
        } else if (node.isObject()) {
            if (node.isEmpty()) {
                out.append("{}");
                return;
            }
            List<String> keys = new ArrayList<>();
            node.propertyNames().forEach(keys::add);
            if (sorted) keys.sort(null);
            out.append('{');
            boolean first = true;
            for (String key : keys) {
                if (!first) out.append(',');
                first = false;
                newline(out, pretty, depth + 1);
                writeString(key, out);
                out.append(':');
                if (pretty) out.append(' ');
                write(node.get(key), out, sorted, pretty, depth + 1);
            }
            newline(out, pretty, depth);
            out.append('}');
        } else {
            writeString(node.asText(), out);
        }
    }

    private static void newline(StringBuilder out, boolean pretty, int depth) {
        if (!pretty) return;
        out.append('\n');
        out.append(INDENT.repeat(depth));
    }

    /** JSON string with the escapes {@code JSON.stringify} and Python's {@code json} share. */
    static void writeString(String text, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
