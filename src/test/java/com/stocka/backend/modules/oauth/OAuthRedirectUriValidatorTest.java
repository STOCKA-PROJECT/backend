package com.stocka.backend.modules.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationException;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

import com.stocka.backend.modules.oauth.client.RegisteredClients;

@DisplayName("OAuthRedirectUriValidator")
class OAuthRedirectUriValidatorTest {

    private final OAuthServerProperties props = new OAuthServerProperties();
    private final OAuthRedirectUriValidator validator = new OAuthRedirectUriValidator(props);
    private final RegisteredClient client = RegisteredClients.publicClient("id", "c", "App",
            List.of("http://localhost/callback", "https://claude.ai/api/mcp/auth_callback"), Instant.EPOCH, props);

    private OAuth2AuthorizationCodeRequestAuthenticationContext context(String redirectUri, Map<String, Object> extra) {
        OAuth2AuthorizationCodeRequestAuthenticationToken token = new OAuth2AuthorizationCodeRequestAuthenticationToken(
                "http://localhost/oauth2/authorize", "c", new TestingAuthenticationToken("1", null), redirectUri, "s",
                Set.of("read"), extra);
        return OAuth2AuthorizationCodeRequestAuthenticationContext.with(token).registeredClient(client).build();
    }

    @Nested
    @DisplayName("redirect_uri")
    class Redirect {

        @Test
        @DisplayName("exact https and loopback-any-port redirects pass")
        void accepted() {
            assertThatCode(() -> validator.accept(context("https://claude.ai/api/mcp/auth_callback", Map.of()))).doesNotThrowAnyException();
            assertThatCode(() -> validator.accept(context("http://localhost:51234/callback", Map.of()))).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("unregistered, fragment or host-less redirects are refused without a redirect")
        void refused() {
            for (String bad : List.of("https://evil.example/cb", "https://claude.ai/api/mcp/auth_callback#x", "/relative")) {
                assertThatThrownBy(() -> validator.accept(context(bad, Map.of())))
                        .isInstanceOf(OAuth2AuthorizationCodeRequestAuthenticationException.class)
                        .satisfies(e -> {
                            var ex = (OAuth2AuthorizationCodeRequestAuthenticationException) e;
                            assertThat(ex.getError().getErrorCode()).isEqualTo("invalid_request");
                            assertThat(ex.getAuthorizationCodeRequestAuthentication()).isNull();
                        });
            }
        }

        @Test
        @DisplayName("without redirect_uri the client must have exactly one registered")
        void missing() {
            assertThatThrownBy(() -> validator.accept(context(null, Map.of())))
                    .isInstanceOf(OAuth2AuthorizationCodeRequestAuthenticationException.class);
        }
    }

    @Nested
    @DisplayName("resource")
    class Resource {

        @Test
        @DisplayName("the MCP endpoint, with or without trailing slash and case, is accepted")
        void accepted() {
            props.setIssuer("http://localhost:9095");
            assertThatCode(() -> validator.accept(context("http://localhost:51234/callback", Map.of("resource", "http://localhost:9095/mcp"))))
                    .doesNotThrowAnyException();
            assertThatCode(() -> validator.accept(context("http://localhost:51234/callback", Map.of("resource", "HTTP://LOCALHOST:9095/mcp/"))))
                    .doesNotThrowAnyException();
            assertThat(OAuthRedirectUriValidator.matchesResource("https://stocka.xeye.es/mcp/", "https://stocka.xeye.es/mcp")).isTrue();
            assertThat(OAuthRedirectUriValidator.matchesResource("https://stocka.xeye.es", "https://stocka.xeye.es/mcp")).isFalse();
            assertThat(OAuthRedirectUriValidator.matchesResource("", "https://stocka.xeye.es/mcp")).isFalse();
        }

        @Test
        @DisplayName("another resource is invalid_target, sent back to the (validated) client")
        void mismatch() {
            assertThatThrownBy(() -> validator.accept(context("http://localhost:51234/callback",
                    Map.of("resource", "https://evil.example/mcp"))))
                    .isInstanceOf(OAuth2AuthorizationCodeRequestAuthenticationException.class)
                    .satisfies(e -> {
                        var ex = (OAuth2AuthorizationCodeRequestAuthenticationException) e;
                        assertThat(ex.getError().getErrorCode()).isEqualTo("invalid_target");
                        assertThat(ex.getAuthorizationCodeRequestAuthentication()).isNotNull();
                    });
        }
    }
}
