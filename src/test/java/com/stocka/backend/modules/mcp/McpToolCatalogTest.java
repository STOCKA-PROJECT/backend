package com.stocka.backend.modules.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.stocka.backend.modules.mcp.prompt.McpPromptRegistry;
import com.stocka.backend.modules.mcp.prompt.PromptSource.PromptDefinition;
import com.stocka.backend.modules.mcp.resource.McpResourceRegistry;
import com.stocka.backend.modules.mcp.resource.ResourceSource.ResourceDefinition;
import com.stocka.backend.modules.mcp.resource.ResourceSource.ResourceTemplateDefinition;
import com.stocka.backend.modules.mcp.tool.McpToolRegistry;
import com.stocka.backend.modules.mcp.tool.ToolDefinition;
import com.stocka.backend.modules.mcp.tool.ToolSchemas;

/**
 * Keeps {@code docs/mcp-tools.md} in sync with the registered tools, resources and prompts. The
 * catalog is rendered from the live registries; run with {@code -Dstocka.docs.write=true} to rewrite
 * the file, otherwise the test fails when the file is stale.
 */
@SpringBootTest
@DisplayName("MCP tool catalog document")
class McpToolCatalogTest {

    static final Path DOC = Path.of("docs", "mcp-tools.md");

    @Autowired private McpToolRegistry tools;
    @Autowired private McpResourceRegistry resources;
    @Autowired private McpPromptRegistry prompts;

    @Test
    @DisplayName("docs/mcp-tools.md matches the registered tools, resources and prompts")
    void catalogIsUpToDate() throws IOException {
        String rendered = render();
        if (Boolean.getBoolean("stocka.docs.write")) {
            Files.createDirectories(DOC.getParent());
            Files.writeString(DOC, rendered, StandardCharsets.UTF_8);
        }
        assertThat(Files.exists(DOC)).as("run with -Dstocka.docs.write=true to generate " + DOC).isTrue();
        assertThat(Files.readString(DOC, StandardCharsets.UTF_8))
                .as("docs/mcp-tools.md is stale: rerun with -Dstocka.docs.write=true")
                .isEqualTo(rendered);
    }

    private String render() {
        StringBuilder out = new StringBuilder();
        out.append("# Stocka MCP: tools, resources and prompts\n\n");
        out.append("Generated from the registered definitions by `McpToolCatalogTest` (`./mvnw test -Dtest=McpToolCatalogTest -Dstocka.docs.write=true`). ");
        out.append("Every tool takes the organization slug as `org` unless stated otherwise; scopes are checked before the organization role.\n\n");
        out.append("## Tools (").append(tools.definitions().size()).append(")\n\n");
        out.append("| Tool | Scope | Hints | Description |\n|---|---|---|---|\n");
        for (ToolDefinition<?> d : tools.definitions()) {
            out.append("| `").append(d.name()).append("` | `").append(d.scope().value()).append("` | ")
                    .append(hints(d)).append(" | ").append(d.description().replace("|", "\\|")).append(" |\n");
        }
        out.append("\n### Parameters\n\n");
        for (ToolDefinition<?> d : tools.definitions()) {
            out.append("#### `").append(d.name()).append("`\n\n");
            Map<String, Object> schema = ToolSchemas.forRecord(d.inputType());
            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
            @SuppressWarnings("unchecked")
            List<String> required = (List<String>) schema.getOrDefault("required", List.of());
            if (properties.isEmpty()) {
                out.append("No arguments.\n\n");
                continue;
            }
            out.append("| Argument | Type | Required | Description |\n|---|---|---|---|\n");
            for (Map.Entry<String, Object> entry : properties.entrySet()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> property = (Map<String, Object>) entry.getValue();
                out.append("| `").append(entry.getKey()).append("` | ").append(type(property)).append(" | ")
                        .append(required.contains(entry.getKey()) ? "yes" : "no").append(" | ")
                        .append(String.valueOf(property.getOrDefault("description", "")).replace("|", "\\|")).append(" |\n");
            }
            out.append('\n');
        }
        out.append("## Resources\n\n| URI | Name | Description |\n|---|---|---|\n");
        for (ResourceDefinition r : resources.resources()) {
            out.append("| `").append(r.uri()).append("` | ").append(r.name()).append(" | ").append(r.description()).append(" |\n");
        }
        for (ResourceTemplateDefinition r : resources.templates()) {
            out.append("| `").append(r.uriTemplate()).append("` | ").append(r.name()).append(" | ").append(r.description()).append(" |\n");
        }
        out.append("\n## Prompts\n\n| Prompt | Arguments | Description |\n|---|---|---|\n");
        for (PromptDefinition p : prompts.prompts()) {
            StringBuilder args = new StringBuilder();
            p.arguments().forEach(a -> args.append('`').append(a.name()).append(a.required() ? "`*" : "`").append(' '));
            out.append("| `").append(p.name()).append("` | ").append(args.toString().trim()).append(" | ")
                    .append(p.description()).append(" |\n");
        }
        return out.toString();
    }

    private static String hints(ToolDefinition<?> d) {
        StringBuilder sb = new StringBuilder();
        if (d.hints().readOnly()) sb.append("read-only ");
        if (d.hints().destructive()) sb.append("destructive ");
        if (d.hints().idempotent()) sb.append("idempotent");
        return sb.toString().trim();
    }

    private static String type(Map<String, Object> property) {
        Object type = property.get("type");
        if (type instanceof List<?> list) {
            type = list.stream().filter(t -> !"null".equals(t)).findFirst().map(Object.class::cast).orElse("any");
        }
        if (property.containsKey("enum")) {
            return "enum " + property.get("enum");
        }
        if (property.containsKey("oneOf")) {
            return "operation object (`op` + fields, see `stocka://guide`)";
        }
        if ("array".equals(type) && property.get("items") instanceof Map<?, ?> items) {
            Object itemType = items.get("type");
            if (itemType instanceof List<?> l) itemType = l.isEmpty() ? "any" : l.get(0);
            return "array of " + (items.containsKey("oneOf") ? "operations" : itemType == null ? "any" : itemType);
        }
        return type == null ? "any" : String.valueOf(type);
    }
}
