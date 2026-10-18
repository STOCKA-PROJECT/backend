package com.stocka.backend.modules.mcp;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.stocka.backend.modules.mcp.prompt.McpPromptRegistry;
import com.stocka.backend.modules.mcp.resource.McpResourceRegistry;
import com.stocka.backend.modules.mcp.tool.McpToolRegistry;

import io.modelcontextprotocol.server.McpStatelessServerFeatures;

/**
 * Publishes Stocka's tools, resources and prompts to the auto-configured stateless MCP server, which
 * collects beans of these list types.
 */
@Configuration
@ConditionalOnProperty(prefix = "stocka.mcp", name = "enabled", havingValue = "true", matchIfMissing = true)
public class McpServerFeaturesConfiguration {

    @Bean
    public List<McpStatelessServerFeatures.SyncToolSpecification> stockaToolSpecifications(McpToolRegistry tools) {
        return tools.specifications();
    }

    @Bean
    public List<McpStatelessServerFeatures.SyncResourceSpecification> stockaResourceSpecifications(
            McpResourceRegistry resources) {
        return resources.resourceSpecifications();
    }

    @Bean
    public List<McpStatelessServerFeatures.SyncResourceTemplateSpecification> stockaResourceTemplateSpecifications(
            McpResourceRegistry resources) {
        return resources.templateSpecifications();
    }

    @Bean
    public List<McpStatelessServerFeatures.SyncPromptSpecification> stockaPromptSpecifications(McpPromptRegistry prompts) {
        return prompts.specifications();
    }
}
