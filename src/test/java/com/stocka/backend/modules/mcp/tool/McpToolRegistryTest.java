package com.stocka.backend.modules.mcp.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

import com.stocka.backend.modules.common.error.ErrorCodes;
import com.stocka.backend.modules.common.schema.Param;
import com.stocka.backend.modules.mcp.McpProperties;
import com.stocka.backend.modules.mcp.McpTransportConfiguration;
import com.stocka.backend.modules.mcp.security.McpAuthentication;
import com.stocka.backend.modules.oauth.OAuthScope;
import com.stocka.backend.modules.roles.entity.Role;
import com.stocka.backend.modules.roles.entity.RoleEnum;
import com.stocka.backend.modules.security.ratelimit.RateLimitPolicy;
import com.stocka.backend.modules.security.ratelimit.RateLimitService;
import com.stocka.backend.modules.users.entity.User;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

import tools.jackson.databind.json.JsonMapper;

@DisplayName("McpToolRegistry")
class McpToolRegistryTest {

    record EchoInput(@Param(value = "text to echo", required = true) String text, @Param("times") Integer times) {
    }

    record EchoResult(String echoed) {
    }

    private final ToolErrorMapper errors = mock(ToolErrorMapper.class);
    private final RateLimitService rateLimit = mock(RateLimitService.class);
    private final JsonMapper json = JsonMapper.builder().build();
    private User user;
    private McpToolRegistry registry;

    private final ToolSource source = () -> List.of(
            ToolDefinition.of("echo", "Echoes", EchoInput.class, OAuthScope.READ, ToolHints.READ,
                    call -> new EchoResult(call.input().text().repeat(call.input().times() == null ? 1 : call.input().times())))
                    .returning(EchoResult.class),
            ToolDefinition.of("whoami", "Reads the security context", EchoInput.class, OAuthScope.READ, ToolHints.READ,
                    call -> new ToolResults.Text(SecurityContextHolder.getContext().getAuthentication().getName())),
            ToolDefinition.of("boom", "Fails", EchoInput.class, OAuthScope.READ, ToolHints.READ,
                    call -> { throw new IllegalStateException("kaboom"); }),
            ToolDefinition.of("nuke", "Needs delete", EchoInput.class, OAuthScope.DELETE, ToolHints.DELETE,
                    call -> ToolResults.Done.of("gone")));

    @BeforeEach
    void setUp() {
        user = new User().setEmail("u@x").setRole(new Role().setName(RoleEnum.USER));
        user.setId(3);
        when(rateLimit.tryAcquire(any(RateLimitPolicy.class), anyString())).thenReturn(0L);
        when(errors.map(any())).thenAnswer(inv -> {
            Throwable t = inv.getArgument(0);
            String code = t instanceof ToolException te ? te.getCode() : ErrorCodes.SERVER_INTERNAL_ERROR;
            return new ToolError(code, t.getMessage() == null ? code : t.getMessage(), "hint", Map.of());
        });
        registry = new McpToolRegistry(List.of(source), json, errors, rateLimit, new McpProperties());
    }

    private McpTransportContext authenticated(Set<OAuthScope> scopes) {
        McpAuthentication auth = new McpAuthentication(user, scopes, "Claude", "jti");
        return McpTransportContext.create(Map.of(McpTransportConfiguration.AUTHENTICATION_KEY, auth));
    }

    private McpSchema.CallToolResult call(String tool, Map<String, Object> args, McpTransportContext ctx) {
        ToolDefinition<?> definition = registry.definitions().stream().filter(d -> d.name().equals(tool)).findFirst().orElseThrow();
        return registry.execute(definition, ctx, new McpSchema.CallToolRequest(tool, args));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> structured(McpSchema.CallToolResult result) {
        return (Map<String, Object>) result.structuredContent();
    }

    @Nested
    @DisplayName("publication")
    class Publication {

        @Test
        @DisplayName("every definition becomes a tool with schemas and annotations; duplicates are refused")
        void specs() {
            List<McpStatelessServerFeatures.SyncToolSpecification> specs = registry.specifications();
            assertThat(specs).hasSize(4);
            McpSchema.Tool echo = specs.get(0).tool();
            assertThat(echo.name()).isEqualTo("echo");
            assertThat(echo.inputSchema()).containsKey("properties");
            assertThat(echo.outputSchema()).containsKey("properties");
            assertThat(echo.annotations().readOnlyHint()).isTrue();
            assertThat(specs.get(3).tool().annotations().destructiveHint()).isTrue();
            assertThat(specs.get(1).tool().outputSchema()).isNull();

            ToolSource duplicate = () -> List.of(ToolDefinition.of("echo", "x", EchoInput.class, OAuthScope.READ,
                    ToolHints.READ, c -> null));
            assertThatThrownBy(() -> new McpToolRegistry(List.of(source, duplicate), json, errors, rateLimit, new McpProperties()))
                    .hasMessageContaining("Duplicate");
        }
    }

    @Nested
    @DisplayName("execution")
    class Execution {

        @Test
        @DisplayName("a successful call returns structured content and its JSON text")
        void success() {
            McpSchema.CallToolResult result = call("echo", Map.of("text", "hi", "times", 2), authenticated(Set.of(OAuthScope.READ)));
            assertThat(result.isError()).isFalse();
            assertThat(structured(result)).containsEntry("echoed", "hihi");
            assertThat(((McpSchema.TextContent) result.content().get(0)).text()).contains("hihi");
        }

        @Test
        @DisplayName("a Text result has no structured content")
        void text() {
            McpSchema.CallToolResult result = call("whoami", Map.of("text", "x"), authenticated(Set.of(OAuthScope.READ)));
            assertThat(result.structuredContent()).isNull();
            assertThat(((McpSchema.TextContent) result.content().get(0)).text()).isEqualTo("3");
            assertThat(SecurityContextHolder.getContext().getAuthentication()).as("context restored").isNull();
        }

        @Test
        @DisplayName("without authentication the call is refused")
        void unauthenticated() {
            McpSchema.CallToolResult result = call("echo", Map.of("text", "hi"), McpTransportContext.EMPTY);
            assertThat(result.isError()).isTrue();
            assertThat(structured(result)).containsEntry("error", ErrorCodes.AUTH_UNAUTHENTICATED);
        }

        @Test
        @DisplayName("a missing scope is reported before running the tool")
        void scope() {
            McpSchema.CallToolResult result = call("nuke", Map.of("text", "x"), authenticated(Set.of(OAuthScope.READ)));
            assertThat(result.isError()).isTrue();
            assertThat(structured(result)).containsEntry("error", ErrorCodes.AUTH_INSUFFICIENT_SCOPE);
            McpSchema.CallToolResult ok = call("nuke", Map.of("text", "x"), authenticated(Set.of(OAuthScope.DELETE)));
            assertThat(ok.isError()).isFalse();
        }

        @Test
        @DisplayName("unconvertible arguments are invalid_arguments")
        void badArguments() {
            McpSchema.CallToolResult result = call("echo", Map.of("text", "hi", "times", "lots"), authenticated(Set.of(OAuthScope.READ)));
            assertThat(result.isError()).isTrue();
            assertThat(structured(result)).containsEntry("error", ErrorCodes.MCP_INVALID_ARGUMENTS);
        }

        @Test
        @DisplayName("handler exceptions go through the error mapper")
        void handlerFailure() {
            McpSchema.CallToolResult result = call("boom", Map.of("text", "x"), authenticated(Set.of(OAuthScope.READ)));
            assertThat(result.isError()).isTrue();
            assertThat(structured(result)).containsEntry("error", ErrorCodes.SERVER_INTERNAL_ERROR).containsEntry("hint", "hint");
        }

        @Test
        @DisplayName("the per-user rate limit answers rate_limited with the retry delay")
        void rateLimited() {
            when(rateLimit.tryAcquire(any(RateLimitPolicy.class), anyString())).thenReturn(12L);
            McpSchema.CallToolResult result = call("echo", Map.of("text", "hi"), authenticated(Set.of(OAuthScope.READ)));
            assertThat(result.isError()).isTrue();
            assertThat(structured(result)).containsEntry("error", ErrorCodes.REQUEST_RATE_LIMITED);
            @SuppressWarnings("unchecked")
            Map<String, Object> params = (Map<String, Object>) structured(result).get("params");
            assertThat(params).containsEntry("retryAfter", 12L);
        }
    }
}
