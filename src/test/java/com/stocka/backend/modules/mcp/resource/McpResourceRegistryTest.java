package com.stocka.backend.modules.mcp.resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.stocka.backend.modules.mcp.McpTransportConfiguration;
import com.stocka.backend.modules.mcp.security.McpAuthentication;
import com.stocka.backend.modules.mcp.tool.ToolErrorMapper;
import com.stocka.backend.modules.oauth.OAuthScope;
import com.stocka.backend.modules.roles.entity.Role;
import com.stocka.backend.modules.roles.entity.RoleEnum;
import com.stocka.backend.modules.users.entity.User;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;

@DisplayName("McpResourceRegistry")
class McpResourceRegistryTest {

    private final ToolErrorMapper errors = mock(ToolErrorMapper.class);

    private McpTransportContext authenticated() {
        User user = new User().setEmail("u@x").setRole(new Role().setName(RoleEnum.USER));
        user.setId(7);
        McpAuthentication auth = new McpAuthentication(user, Set.of(OAuthScope.READ), "Claude", "jti");
        return McpTransportContext.create(Map.of(McpTransportConfiguration.AUTHENTICATION_KEY, auth));
    }

    private final ResourceSource source = new ResourceSource() {
        @Override
        public List<ResourceDefinition> resources() {
            return List.of(new ResourceDefinition("stocka://guide", "Guide", "d", "text/markdown",
                    caller -> "hello " + caller.user().getEmail()));
        }

        @Override
        public List<ResourceTemplateDefinition> templates() {
            return List.of(new ResourceTemplateDefinition("stocka://org/{slug}/overview", "Overview", "d",
                    "text/markdown", (caller, vars) -> "org " + vars.get("slug")));
        }
    };

    @Nested
    @DisplayName("templates")
    class Templates {

        @Test
        @DisplayName("variables are extracted from matching URIs only")
        void variables() {
            assertThat(McpResourceRegistry.variables("stocka://org/{slug}/overview", "stocka://org/acme/overview"))
                    .containsEntry("slug", "acme");
            assertThat(McpResourceRegistry.variables("stocka://org/{slug}/overview", "stocka://org/a/b/overview")).isEmpty();
            assertThat(McpResourceRegistry.variables("stocka://x/{a}/{b}", "stocka://x/1/2"))
                    .containsEntry("a", "1").containsEntry("b", "2");
        }
    }

    @Nested
    @DisplayName("specifications")
    class Specifications {

        @Test
        @DisplayName("resources and templates are published and read with the caller")
        void reads() {
            McpResourceRegistry registry = new McpResourceRegistry(List.of(source), errors);
            List<McpStatelessServerFeatures.SyncResourceSpecification> resources = registry.resourceSpecifications();
            assertThat(resources).hasSize(1);
            assertThat(resources.get(0).resource().uri()).isEqualTo("stocka://guide");
            McpSchema.ReadResourceResult result = resources.get(0).readHandler()
                    .apply(authenticated(), new McpSchema.ReadResourceRequest("stocka://guide"));
            assertThat(((McpSchema.TextResourceContents) result.contents().get(0)).text()).isEqualTo("hello u@x");

            List<McpStatelessServerFeatures.SyncResourceTemplateSpecification> templates = registry.templateSpecifications();
            McpSchema.ReadResourceResult org = templates.get(0).readHandler()
                    .apply(authenticated(), new McpSchema.ReadResourceRequest("stocka://org/acme/overview"));
            assertThat(((McpSchema.TextResourceContents) org.contents().get(0)).text()).isEqualTo("org acme");
        }

        @Test
        @DisplayName("without authentication a resource read is a JSON-RPC error")
        void unauthenticated() {
            McpResourceRegistry registry = new McpResourceRegistry(List.of(source), errors);
            assertThatThrownBy(() -> registry.resourceSpecifications().get(0).readHandler()
                    .apply(McpTransportContext.EMPTY, new McpSchema.ReadResourceRequest("stocka://guide")))
                    .isInstanceOf(McpError.class);
        }
    }
}
