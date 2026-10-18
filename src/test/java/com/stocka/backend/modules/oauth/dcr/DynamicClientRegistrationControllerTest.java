package com.stocka.backend.modules.oauth.dcr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

import com.stocka.backend.modules.oauth.OAuthServerProperties;
import com.stocka.backend.modules.oauth.dcr.DynamicClientRegistrationController.RegistrationException;

@DisplayName("DynamicClientRegistrationController")
class DynamicClientRegistrationControllerTest {

    private final RegisteredClientRepository clients = mock(RegisteredClientRepository.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final OAuthServerProperties props = new OAuthServerProperties();
    private final Instant now = Instant.parse("2026-10-18T10:00:00Z");
    private final DynamicClientRegistrationController controller =
            new DynamicClientRegistrationController(clients, jdbc, props, Clock.fixed(now, ZoneOffset.UTC));

    private DynamicClientRegistrationRequest request(List<String> uris) {
        return new DynamicClientRegistrationRequest("My client", uris, List.of("authorization_code", "refresh_token"),
                List.of("code"), "none", "native", "https://my.example", "https://my.example/logo.png", null);
    }

    @Nested
    @DisplayName("register")
    class Register {

        @Test
        @DisplayName("stores a public client and answers the RFC 7591 response")
        void ok() {
            ResponseEntity<Map<String, Object>> response = controller.register(request(List.of("http://localhost/cb")));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            Map<String, Object> body = response.getBody();
            assertThat(body).containsEntry("client_name", "My client")
                    .containsEntry("token_endpoint_auth_method", "none")
                    .containsEntry("client_id_issued_at", now.getEpochSecond())
                    .containsEntry("client_uri", "https://my.example")
                    .containsKey("client_id");
            ArgumentCaptor<RegisteredClient> saved = ArgumentCaptor.forClass(RegisteredClient.class);
            verify(clients).save(saved.capture());
            assertThat(saved.getValue().getClientAuthenticationMethods()).containsExactly(ClientAuthenticationMethod.NONE);
            assertThat(saved.getValue().getClientSettings().isRequireProofKey()).isTrue();
            assertThat(saved.getValue().getRedirectUris()).containsExactly("http://localhost/cb");
            verify(jdbc).update(anyString(), eq("https://my.example"), eq("https://my.example/logo.png"), eq(saved.getValue().getId()));
        }

        @Test
        @DisplayName("is a 404 when disabled")
        void disabled() {
            props.getDcr().setEnabled(false);
            ResponseEntity<Map<String, Object>> response = controller.register(request(List.of("http://localhost/cb")));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            verify(clients, never()).save(any());
        }

        @Test
        @DisplayName("invalid metadata is a 400 with the RFC error code")
        void invalid() {
            ResponseEntity<Map<String, Object>> response = controller.register(request(List.of("http://evil.example/cb")));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).containsEntry("error", "invalid_redirect_uri");
            verify(clients, never()).save(any());
        }

        @Test
        @DisplayName("non-https client links are dropped")
        void dropsHttpLinks() {
            DynamicClientRegistrationRequest req = new DynamicClientRegistrationRequest("c", List.of("http://localhost/cb"),
                    null, null, null, null, "http://insecure.example", null, null);
            ResponseEntity<Map<String, Object>> response = controller.register(req);
            assertThat(response.getBody()).doesNotContainKey("client_uri");
        }
    }

    @Nested
    @DisplayName("validation rules")
    class Rules {

        @Test
        @DisplayName("redirect URIs: required, bounded, registrable, de-duplicated")
        void redirectUris() {
            assertThatThrownBy(() -> DynamicClientRegistrationController.validatedRedirectUris(null))
                    .isInstanceOf(RegistrationException.class).hasMessageContaining("required");
            assertThatThrownBy(() -> DynamicClientRegistrationController.validatedRedirectUris(List.of()))
                    .hasMessageContaining("required");
            assertThatThrownBy(() -> DynamicClientRegistrationController.validatedRedirectUris(
                    java.util.Collections.nCopies(11, "https://a.example/cb"))).hasMessageContaining("too many");
            assertThatThrownBy(() -> DynamicClientRegistrationController.validatedRedirectUris(List.of("http://a.example/cb")))
                    .hasMessageContaining("loopback");
            assertThat(DynamicClientRegistrationController.validatedRedirectUris(
                    List.of("https://a.example/cb", " https://a.example/cb", "https://a.example/cb")))
                    .containsExactly("https://a.example/cb");
        }

        @Test
        @DisplayName("name defaults to the redirect host and is capped")
        void name() {
            assertThat(DynamicClientRegistrationController.validatedName(null, "https://claude.ai/api/cb")).isEqualTo("claude.ai");
            assertThat(DynamicClientRegistrationController.validatedName("  ", "http://localhost:1/cb")).isEqualTo("localhost");
            assertThat(DynamicClientRegistrationController.validatedName("x".repeat(300), "https://a/b")).hasSize(120);
        }

        @Test
        @DisplayName("grants, response types and auth method")
        void grants() {
            DynamicClientRegistrationController.validateGrants(null);
            DynamicClientRegistrationController.validateGrants(List.of());
            DynamicClientRegistrationController.validateGrants(List.of("authorization_code"));
            DynamicClientRegistrationController.validateGrants(
                    List.of("authorization_code", "refresh_token", "urn:ietf:params:oauth:grant-type:jwt-bearer"));
            assertThatThrownBy(() -> DynamicClientRegistrationController.validateGrants(List.of("client_credentials")))
                    .hasMessageContaining("authorization_code");
            DynamicClientRegistrationController.validateResponseTypes(List.of("code"));
            assertThatThrownBy(() -> DynamicClientRegistrationController.validateResponseTypes(List.of("token")))
                    .hasMessageContaining("response_type");
            DynamicClientRegistrationController.validateAuthMethod(null);
            DynamicClientRegistrationController.validateAuthMethod("none");
            assertThatThrownBy(() -> DynamicClientRegistrationController.validateAuthMethod("client_secret_post"))
                    .hasMessageContaining("public");
        }
    }
}
