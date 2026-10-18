package com.stocka.backend.modules.mcp.tool;

import com.stocka.backend.modules.oauth.OAuthScope;

/**
 * Everything the registry needs to publish and run one tool.
 *
 * @param name        tool name, {@code snake_case}, {@code <domain>_<verb>}
 * @param description what it does, when to use it and what it returns (English, for the model)
 * @param inputType   record describing the arguments (see {@link Param})
 * @param outputType  record describing the result, or {@code null} to skip the output schema
 * @param scope       scope the caller must hold
 * @param hints       MCP tool annotations
 * @param handler     the body
 * @param <I>         input type
 */
public record ToolDefinition<I>(
        String name,
        String description,
        Class<I> inputType,
        Class<?> outputType,
        OAuthScope scope,
        ToolHints hints,
        ToolHandler<I> handler
) {

    /** Starts a definition without output schema. */
    public static <I> ToolDefinition<I> of(String name, String description, Class<I> inputType, OAuthScope scope,
                                           ToolHints hints, ToolHandler<I> handler) {
        return new ToolDefinition<>(name, description, inputType, null, scope, hints, handler);
    }

    /** @return the same definition advertising {@code outputType} as its result schema */
    public ToolDefinition<I> returning(Class<?> outputType) {
        return new ToolDefinition<>(name, description, inputType, outputType, scope, hints, handler);
    }
}
