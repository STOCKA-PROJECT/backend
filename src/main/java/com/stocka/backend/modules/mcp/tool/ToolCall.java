package com.stocka.backend.modules.mcp.tool;

/**
 * One invocation of a tool: the parsed input and the caller.
 *
 * @param input  arguments converted to the tool's input record
 * @param caller who is calling
 * @param <I>    input type
 */
public record ToolCall<I>(I input, McpCaller caller) {
}
