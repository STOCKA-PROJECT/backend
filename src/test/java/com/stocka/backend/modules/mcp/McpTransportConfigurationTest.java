package com.stocka.backend.modules.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.web.servlet.function.ServerRequest;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpTransportContextExtractor;

@DisplayName("McpTransportConfiguration: context extractor")
class McpTransportConfigurationTest {

    private final McpTransportContextExtractor<ServerRequest> extractor =
            new McpTransportConfiguration().mcpTransportContextExtractor();

    @AfterEach
    void clearHolder() {
        SecurityContextHolder.clearContext();
    }

    private static ServerRequest request(Authentication savedInRequest) {
        MockHttpServletRequest servlet = new MockHttpServletRequest("POST", "/mcp");
        if (savedInRequest != null) {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(savedInRequest);
            servlet.setAttribute(RequestAttributeSecurityContextRepository.DEFAULT_REQUEST_ATTR_NAME, context);
        }
        return ServerRequest.create(servlet, List.of());
    }

    private static Authentication token(String name) {
        return new TestingAuthenticationToken(name, "n/a", "ROLE_USER");
    }

    @Nested
    @DisplayName("source of the authentication")
    class Sources {

        @Test
        @DisplayName("prefers the context the resource server saved in the request attributes")
        void requestAttributeWins() {
            SecurityContextHolder.getContext().setAuthentication(token("holder"));

            McpTransportContext context = extractor.extract(request(token("request")));

            assertThat(context.get(McpTransportConfiguration.AUTHENTICATION_KEY))
                    .isInstanceOfSatisfying(Authentication.class, a -> assertThat(a.getName()).isEqualTo("request"));
        }

        @Test
        @DisplayName("falls back to the thread-bound holder")
        void holderFallback() {
            SecurityContextHolder.getContext().setAuthentication(token("holder"));

            McpTransportContext context = extractor.extract(request(null));

            assertThat(context.get(McpTransportConfiguration.AUTHENTICATION_KEY))
                    .isInstanceOfSatisfying(Authentication.class, a -> assertThat(a.getName()).isEqualTo("holder"));
        }

        @Test
        @DisplayName("leaves the key out for anonymous requests")
        void anonymous() {
            McpTransportContext context = extractor.extract(request(null));

            assertThat(context.get(McpTransportConfiguration.AUTHENTICATION_KEY)).isNull();
        }
    }
}
