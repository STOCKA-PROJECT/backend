package com.stocka.backend.modules.mcp.tool;

import java.util.List;
import java.util.Map;

/**
 * Small helpers for tool results.
 */
public final class ToolResults {

    private ToolResults() {
    }

    /** A plain-text result (no structured content). */
    public record Text(String text) {
    }

    /**
     * A page of items.
     *
     * @param items      the page
     * @param page       zero-based page index
     * @param size       page size
     * @param total      total items
     * @param totalPages total pages
     */
    public record Page<T>(List<T> items, int page, int size, long total, int totalPages) {
        public static <T> Page<T> of(org.springframework.data.domain.Page<T> page) {
            return new Page<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(),
                    page.getTotalPages());
        }
    }

    /** Acknowledgement of a change. */
    public record Done(boolean ok, String message) {
        public static Done of(String message) {
            return new Done(true, message);
        }
    }

    /** @return {@code text} cut to {@code max} characters with a marker when longer */
    public static String truncate(String text, int max) {
        if (text == null || text.length() <= max) return text;
        return text.substring(0, Math.max(0, max - 1)) + "…";
    }

    /** @return a single-entry map, handy for ad-hoc structured results */
    public static Map<String, Object> single(String key, Object value) {
        return Map.of(key, value);
    }
}
