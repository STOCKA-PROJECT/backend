package com.stocka.backend.modules.mcp;

import java.util.HashMap;
import java.util.Map;

import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStatelessServerTransport;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.function.ServerRequest;

import com.stocka.backend.modules.mcp.security.McpSecurityConfiguration;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpTransportContextExtractor;

import tools.jackson.databind.json.JsonMapper;

/**
 * The Streamable HTTP (stateless) transport of the MCP server.
 *
 * <p>Replaces the auto-configured transport for one reason: tool handlers run on a worker thread of
 * the MCP SDK, not on the servlet thread, so the {@link Authentication} established by the resource
 * server filter chain must travel with the request. The context extractor copies it into the
 * {@link McpTransportContext}; the tool registry restores it around each call.
 */
@Configuration
@ConditionalOnProperty(prefix = "stocka.mcp", name = "enabled", havingValue = "true", matchIfMissing = true)
public class McpTransportConfiguration {

    /** Key of the {@link Authentication} inside the transport context. */
    public static final String AUTHENTICATION_KEY = "stocka.authentication";

    @Bean
    public McpJsonMapper mcpJsonMapper(JsonMapper jsonMapper) {
        return new JacksonMcpJsonMapper(jsonMapper);
    }

    @Bean
    public McpTransportContextExtractor<ServerRequest> mcpTransportContextExtractor() {
        return request -> {
            Map<String, Object> values = new HashMap<>();
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null) {
                values.put(AUTHENTICATION_KEY, authentication);
            }
            return McpTransportContext.create(values);
        };
    }

    @Bean
    public WebMvcStatelessServerTransport webMvcStatelessServerTransport(
            McpJsonMapper mcpJsonMapper, McpTransportContextExtractor<ServerRequest> extractor) {
        return WebMvcStatelessServerTransport.builder()
                .jsonMapper(mcpJsonMapper)
                .messageEndpoint(McpSecurityConfiguration.MCP_PATH)
                .contextExtractor(extractor)
                .build();
    }
}
