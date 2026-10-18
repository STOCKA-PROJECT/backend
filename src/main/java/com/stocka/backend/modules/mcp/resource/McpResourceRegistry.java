package com.stocka.backend.modules.mcp.resource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.stocka.backend.modules.mcp.resource.ResourceSource.ResourceDefinition;
import com.stocka.backend.modules.mcp.resource.ResourceSource.ResourceTemplateDefinition;
import com.stocka.backend.modules.mcp.security.McpAuthentication;
import com.stocka.backend.modules.mcp.tool.McpCaller;
import com.stocka.backend.modules.mcp.tool.McpToolRegistry;
import com.stocka.backend.modules.mcp.tool.ToolErrorMapper;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Collects every {@link ResourceSource} and publishes resources and resource templates to the MCP
 * server. Template variables ({@code {slug}}) are extracted from the requested URI before calling
 * the reader. Failures surface as JSON-RPC errors with the same codes as tool errors.
 */
@Component
public class McpResourceRegistry {

    private static final Logger log = LoggerFactory.getLogger(McpResourceRegistry.class);

    private final List<ResourceDefinition> resources = new ArrayList<>();
    private final List<ResourceTemplateDefinition> templates = new ArrayList<>();
    private final ToolErrorMapper errors;

    public McpResourceRegistry(List<ResourceSource> sources, ToolErrorMapper errors) {
        this.errors = errors;
        for (ResourceSource source : sources) {
            resources.addAll(source.resources());
            templates.addAll(source.templates());
        }
        log.info("mcp_resources registered={} templates={}", resources.size(), templates.size());
    }

    public List<ResourceDefinition> resources() {
        return List.copyOf(resources);
    }

    public List<ResourceTemplateDefinition> templates() {
        return List.copyOf(templates);
    }

    /** @return SDK specifications of the fixed resources */
    public List<McpStatelessServerFeatures.SyncResourceSpecification> resourceSpecifications() {
        List<McpStatelessServerFeatures.SyncResourceSpecification> out = new ArrayList<>();
        for (ResourceDefinition definition : resources) {
            McpSchema.Resource resource = McpSchema.Resource.builder()
                    .uri(definition.uri())
                    .name(definition.name())
                    .description(definition.description())
                    .mimeType(definition.mimeType())
                    .build();
            out.add(new McpStatelessServerFeatures.SyncResourceSpecification(resource, (context, request) ->
                    read(request.uri(), definition.mimeType(),
                            () -> definition.reader().apply(callerOf(context)))));
        }
        return out;
    }

    /** @return SDK specifications of the resource templates */
    public List<McpStatelessServerFeatures.SyncResourceTemplateSpecification> templateSpecifications() {
        List<McpStatelessServerFeatures.SyncResourceTemplateSpecification> out = new ArrayList<>();
        for (ResourceTemplateDefinition definition : templates) {
            McpSchema.ResourceTemplate template = McpSchema.ResourceTemplate.builder()
                    .uriTemplate(definition.uriTemplate())
                    .name(definition.name())
                    .description(definition.description())
                    .mimeType(definition.mimeType())
                    .build();
            out.add(new McpStatelessServerFeatures.SyncResourceTemplateSpecification(template, (context, request) ->
                    read(request.uri(), definition.mimeType(), () -> {
                        Map<String, String> variables = variables(definition.uriTemplate(), request.uri());
                        return definition.reader().apply(callerOf(context), variables);
                    })));
        }
        return out;
    }

    private McpSchema.ReadResourceResult read(String uri, String mimeType, java.util.function.Supplier<String> body) {
        try {
            String text = body.get();
            return new McpSchema.ReadResourceResult(List.of(new McpSchema.TextResourceContents(uri, mimeType, text)));
        } catch (McpError e) {
            throw e;
        } catch (RuntimeException e) {
            var error = errors.map(e);
            throw McpError.builder(McpSchema.ErrorCodes.INVALID_PARAMS)
                    .message(error.code() + ": " + error.message())
                    .build();
        }
    }

    static McpCaller callerOf(McpTransportContext context) {
        McpAuthentication authentication = McpToolRegistry.authenticationOf(context);
        if (authentication == null) {
            throw McpError.builder(McpSchema.ErrorCodes.INVALID_REQUEST).message("auth.unauthenticated").build();
        }
        return new McpCaller(authentication.getUser(), authentication.getScopes(), authentication.getClientName());
    }

    /**
     * Extracts {@code {name}} variables of {@code template} from {@code uri}.
     *
     * @return the variables, empty when the URI does not match
     */
    static Map<String, String> variables(String template, String uri) {
        List<String> names = new ArrayList<>();
        StringBuilder regex = new StringBuilder();
        Matcher m = Pattern.compile("\\{([a-zA-Z0-9_]+)}").matcher(template);
        int last = 0;
        while (m.find()) {
            regex.append(Pattern.quote(template.substring(last, m.start())));
            regex.append("([^/]+)");
            names.add(m.group(1));
            last = m.end();
        }
        regex.append(Pattern.quote(template.substring(last)));
        Matcher match = Pattern.compile("^" + regex + "$").matcher(uri);
        Map<String, String> out = new LinkedHashMap<>();
        if (match.matches()) {
            for (int i = 0; i < names.size(); i++) {
                out.put(names.get(i), match.group(i + 1));
            }
        }
        return out;
    }
}
