package com.stocka.backend.modules.mcp.tool.support;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Paging arguments of list tools, clamped to the configured limits.
 */
public final class Pages {

    private Pages() {
    }

    /**
     * @param page        zero-based page or {@code null}
     * @param size        page size or {@code null}
     * @param defaultSize default size
     * @param maxSize     hard cap
     * @param sort        sort to apply
     * @return the pageable
     */
    public static Pageable of(Integer page, Integer size, int defaultSize, int maxSize, Sort sort) {
        int p = page == null || page < 0 ? 0 : page;
        int s = size == null || size <= 0 ? defaultSize : Math.min(size, maxSize);
        return PageRequest.of(p, s, sort);
    }
}
