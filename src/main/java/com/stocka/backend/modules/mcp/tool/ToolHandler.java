package com.stocka.backend.modules.mcp.tool;

/**
 * The body of a tool. Returns the result object (a record or map serialized as structured content,
 * or a {@link ToolResults.Text}); throws to report an error (see {@link ToolErrorMapper}).
 *
 * @param <I> input type
 */
@FunctionalInterface
public interface ToolHandler<I> {

    Object handle(ToolCall<I> call);
}
