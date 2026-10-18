package com.stocka.backend.modules.mcp.resource;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

import com.stocka.backend.modules.mcp.tool.McpCaller;

/**
 * A bean that contributes MCP resources (reference documents the model can read) and resource
 * templates (parameterized URIs such as {@code stocka://org/{slug}/overview}).
 */
public interface ResourceSource {

    /** A fixed resource. The reader receives the caller and returns the text. */
    record ResourceDefinition(String uri, String name, String description, String mimeType,
                              Function<McpCaller, String> reader) {
    }

    /** A templated resource. The reader receives the caller and the variables extracted from the URI. */
    record ResourceTemplateDefinition(String uriTemplate, String name, String description, String mimeType,
                                      BiFunction<McpCaller, Map<String, String>, String> reader) {
    }

    default List<ResourceDefinition> resources() {
        return List.of();
    }

    default List<ResourceTemplateDefinition> templates() {
        return List.of();
    }
}
