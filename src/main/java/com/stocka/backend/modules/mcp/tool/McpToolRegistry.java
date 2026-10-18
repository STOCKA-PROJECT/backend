package com.stocka.backend.modules.mcp.tool;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.stocka.backend.modules.common.error.ErrorCodes;
import com.stocka.backend.modules.mcp.McpProperties;
import com.stocka.backend.modules.mcp.McpTransportConfiguration;
import com.stocka.backend.modules.mcp.security.McpAuthentication;
import com.stocka.backend.modules.security.ratelimit.RateLimitPolicy;
import com.stocka.backend.modules.security.ratelimit.RateLimitService;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Collects every {@link ToolSource}, publishes the tools to the MCP server and runs them.
 *
 * <p>This is the only class that talks to the MCP SDK. Running a tool means: find the caller in the
 * transport context (the resource server put the {@link McpAuthentication} there), check the scope,
 * apply the per-user rate limit, convert the JSON arguments into the input record, restore the
 * security context on the worker thread (services read the current user from it), invoke the
 * handler and serialize the result as structured content plus its JSON text. Any exception becomes
 * an {@code isError} result through {@link ToolErrorMapper}.
 */
@Component
public class McpToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(McpToolRegistry.class);

    private final Map<String, ToolDefinition<?>> definitions = new LinkedHashMap<>();
    private final JsonMapper json;
    private final ToolErrorMapper errors;
    private final RateLimitService rateLimit;
    private final RateLimitPolicy userPolicy;

    public McpToolRegistry(List<ToolSource> sources, JsonMapper json, ToolErrorMapper errors,
                           RateLimitService rateLimit, McpProperties props) {
        this.json = json;
        this.errors = errors;
        this.rateLimit = rateLimit;
        this.userPolicy = new RateLimitPolicy("mcp.calls.user", props.getUserCallsPerMinute(),
                props.getUserCallsPerMinute(), Duration.ofMinutes(1));
        Set<String> names = new HashSet<>();
        for (ToolSource source : sources) {
            for (ToolDefinition<?> definition : source.tools()) {
                if (!names.add(definition.name())) {
                    throw new IllegalStateException("Duplicate MCP tool name: " + definition.name());
                }
                definitions.put(definition.name(), definition);
            }
        }
        log.info("mcp_tools registered={}", definitions.size());
    }

    /** @return the registered definitions, in registration order */
    public List<ToolDefinition<?>> definitions() {
        return List.copyOf(definitions.values());
    }

    /** @return the SDK specifications for every tool */
    public List<McpStatelessServerFeatures.SyncToolSpecification> specifications() {
        List<McpStatelessServerFeatures.SyncToolSpecification> out = new ArrayList<>();
        for (ToolDefinition<?> definition : definitions.values()) {
            out.add(new McpStatelessServerFeatures.SyncToolSpecification(toSchema(definition),
                    (context, request) -> execute(definition, context, request)));
        }
        return out;
    }

    /** @return the MCP tool descriptor (name, description, schemas, annotations) */
    public static McpSchema.Tool toSchema(ToolDefinition<?> definition) {
        McpSchema.Tool.Builder builder = McpSchema.Tool.builder(definition.name())
                .description(definition.description())
                .inputSchema(ToolSchemas.forRecord(definition.inputType()))
                .annotations(new McpSchema.ToolAnnotations(null, definition.hints().readOnly(),
                        definition.hints().destructive(), definition.hints().idempotent(), false, null));
        if (definition.outputType() != null) {
            builder.outputSchema(ToolSchemas.forOutput(definition.outputType()));
        }
        return builder.build();
    }

    /**
     * Runs one tool call end to end.
     *
     * @param definition the tool
     * @param context    transport context carrying the authentication
     * @param request    JSON-RPC request
     * @return the result, or an error result
     */
    public McpSchema.CallToolResult execute(ToolDefinition<?> definition, McpTransportContext context,
                                            McpSchema.CallToolRequest request) {
        long started = System.nanoTime();
        McpAuthentication authentication = authenticationOf(context);
        if (authentication == null) {
            return error(new ToolError(ErrorCodes.AUTH_UNAUTHENTICATED, "No valid access token", null, Map.of()));
        }
        McpCaller caller = new McpCaller(authentication.getUser(), authentication.getScopes(),
                authentication.getClientName());
        String outcome = "ok";
        try {
            if (!caller.has(definition.scope())) {
                outcome = ErrorCodes.AUTH_INSUFFICIENT_SCOPE;
                return error(errors.map(new ToolException(ErrorCodes.AUTH_INSUFFICIENT_SCOPE, null, null,
                        Map.of("scope", definition.scope().value()))));
            }
            long retryAfter = rateLimit.tryAcquire(userPolicy, String.valueOf(caller.user().getId()));
            if (retryAfter > 0) {
                outcome = ErrorCodes.REQUEST_RATE_LIMITED;
                return error(new ToolError(ErrorCodes.REQUEST_RATE_LIMITED, "Too many tool calls; retry in "
                        + retryAfter + " s", "Wait before calling more tools.", Map.of("retryAfter", retryAfter)));
            }
            Object result = runWithSecurityContext(authentication, () -> invoke(definition, request, caller));
            return success(result);
        } catch (RuntimeException ex) {
            ToolError mapped = errors.map(ex);
            outcome = mapped.code();
            return error(mapped);
        } finally {
            long ms = (System.nanoTime() - started) / 1_000_000;
            log.info("mcp_tool name={} user={} client={} ms={} outcome={}", definition.name(),
                    caller.user().getId(), caller.clientName(), ms, outcome);
        }
    }

    private <I> Object invoke(ToolDefinition<I> definition, McpSchema.CallToolRequest request, McpCaller caller) {
        I input;
        try {
            Map<String, Object> arguments = request.arguments() == null ? Map.of() : request.arguments();
            input = json.convertValue(arguments, definition.inputType());
        } catch (JacksonException | IllegalArgumentException ex) {
            throw new ToolException(ErrorCodes.MCP_INVALID_ARGUMENTS, null,
                    "Check the input schema of the tool and resend the call.", Map.of("detail", rootMessage(ex)));
        }
        if (input == null) {
            throw new ToolException(ErrorCodes.MCP_INVALID_ARGUMENTS, null, null, Map.of("detail", "no arguments"));
        }
        return definition.handler().handle(new ToolCall<>(input, caller));
    }

    private static Object runWithSecurityContext(Authentication authentication, java.util.function.Supplier<Object> body) {
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        try {
            return body.get();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    /** @return the authentication the resource server stored in the transport context, if any */
    public static McpAuthentication authenticationOf(McpTransportContext context) {
        Object value = context == null ? null : context.get(McpTransportConfiguration.AUTHENTICATION_KEY);
        return value instanceof McpAuthentication auth && auth.isAuthenticated() ? auth : null;
    }

    private McpSchema.CallToolResult success(Object result) {
        if (result instanceof ToolResults.Text text) {
            return McpSchema.CallToolResult.builder().addTextContent(text.text()).isError(false).build();
        }
        Object structured = result == null ? Map.of() : json.convertValue(result, Object.class);
        if (!(structured instanceof Map)) {
            structured = Collections.singletonMap("value", structured);
        }
        String text = json.writeValueAsString(structured);
        return McpSchema.CallToolResult.builder()
                .structuredContent(structured)
                .addTextContent(text)
                .isError(false)
                .build();
    }

    private McpSchema.CallToolResult error(ToolError error) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error.code());
        body.put("message", error.message());
        if (error.hint() != null) body.put("hint", error.hint());
        if (error.params() != null && !error.params().isEmpty()) body.put("params", error.params());
        return McpSchema.CallToolResult.builder()
                .structuredContent(body)
                .addTextContent(json.writeValueAsString(body))
                .isError(true)
                .build();
    }

    private static String rootMessage(Throwable ex) {
        Throwable t = ex;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String message = t.getMessage();
        if (message == null) return t.getClass().getSimpleName();
        int cut = message.indexOf('\n');
        return cut > 0 ? message.substring(0, cut) : message;
    }
}
