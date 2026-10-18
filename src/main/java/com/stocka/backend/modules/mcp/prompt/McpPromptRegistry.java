package com.stocka.backend.modules.mcp.prompt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.stocka.backend.modules.mcp.prompt.PromptSource.Argument;
import com.stocka.backend.modules.mcp.prompt.PromptSource.PromptDefinition;

import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Collects every {@link PromptSource} and publishes the prompts to the MCP server.
 */
@Component
public class McpPromptRegistry {

    private static final Logger log = LoggerFactory.getLogger(McpPromptRegistry.class);

    private final List<PromptDefinition> prompts = new ArrayList<>();

    public McpPromptRegistry(List<PromptSource> sources) {
        for (PromptSource source : sources) {
            prompts.addAll(source.prompts());
        }
        log.info("mcp_prompts registered={}", prompts.size());
    }

    public List<PromptDefinition> prompts() {
        return List.copyOf(prompts);
    }

    /** @return SDK specifications of every prompt */
    public List<McpStatelessServerFeatures.SyncPromptSpecification> specifications() {
        List<McpStatelessServerFeatures.SyncPromptSpecification> out = new ArrayList<>();
        for (PromptDefinition definition : prompts) {
            List<McpSchema.PromptArgument> arguments = new ArrayList<>();
            for (Argument argument : definition.arguments()) {
                arguments.add(new McpSchema.PromptArgument(argument.name(), argument.description(), argument.required()));
            }
            McpSchema.Prompt prompt = new McpSchema.Prompt(definition.name(), definition.title(),
                    definition.description(), arguments);
            out.add(new McpStatelessServerFeatures.SyncPromptSpecification(prompt, (context, request) -> {
                Map<String, String> values = new LinkedHashMap<>();
                if (request.arguments() != null) {
                    request.arguments().forEach((k, v) -> values.put(k, v == null ? "" : String.valueOf(v)));
                }
                for (Argument argument : definition.arguments()) {
                    if (argument.required() && (values.get(argument.name()) == null || values.get(argument.name()).isBlank())) {
                        throw McpError.builder(McpSchema.ErrorCodes.INVALID_PARAMS)
                                .message("Missing prompt argument: " + argument.name()).build();
                    }
                }
                String text = definition.renderer().apply(values);
                return new McpSchema.GetPromptResult(definition.description(), List.of(
                        new McpSchema.PromptMessage(McpSchema.Role.USER, new McpSchema.TextContent(text))));
            }));
        }
        return out;
    }
}
