package com.stocka.backend.modules.mcp.tool;

import java.util.Map;

/**
 * The structured error a tool returns ({@code isError: true}).
 *
 * @param code    stable code, e.g. {@code pieces.not_found}
 * @param message English message
 * @param hint    what the model should do next, may be {@code null}
 * @param params  named details (ids, limits), may be empty
 */
public record ToolError(String code, String message, String hint, Map<String, Object> params) {
}
