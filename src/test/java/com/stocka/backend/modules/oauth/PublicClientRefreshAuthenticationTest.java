package com.stocka.backend.modules.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

import com.stocka.backend.modules.oauth.client.RegisteredClients;

@DisplayName("PublicClientRefreshAuthentication")
class PublicClientRefreshAuthenticationTest {

    private final PublicClientRefreshAuthentication.Converter converter = new PublicClientRefreshAuthentication.Converter();

    private MockHttpServletRequest refreshRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/oauth2/token");
        request.setParameter("grant_type", "refresh_token");
        request.setParameter("refresh_token", "rt");
        request.setParameter("client_id", "abc");
        return request;
    }

    @Nested
    @DisplayName("converter")
    class ConverterCases {

        @Test
        @DisplayName("claims a refresh_token grant with client_id and no secret")
        void refresh() {
            Authentication auth = converter.convert(refreshRequest());
            assertThat(auth).isInstanceOf(OAuth2ClientAuthenticationToken.class);
            OAuth2ClientAuthenticationToken token = (OAuth2ClientAuthenticationToken) auth;
            assertThat(token.getPrincipal()).isEqualTo("abc");
            assertThat(token.getClientAuthenticationMethod()).isEqualTo(ClientAuthenticationMethod.NONE);
            assertThat(token.getAdditionalParameters()).containsEntry("refresh_token", "rt").doesNotContainKey("client_id");
        }

        @Test
        @DisplayName("claims a revocation request too")
        void revoke() {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/oauth2/revoke");
            request.setParameter("token", "t");
            request.setParameter("client_id", "abc");
            assertThat(converter.convert(request)).isNotNull();
        }

        @Test
        @DisplayName("ignores authorization_code grants, secrets, Authorization headers, GETs and missing client_id")
        void ignores() {
            MockHttpServletRequest code = refreshRequest();
            code.setParameter("grant_type", "authorization_code");
            assertThat(converter.convert(code)).isNull();

            MockHttpServletRequest secret = refreshRequest();
            secret.setParameter("client_secret", "s");
            assertThat(converter.convert(secret)).isNull();

            MockHttpServletRequest header = refreshRequest();
            header.addHeader("Authorization", "Basic abc");
            assertThat(converter.convert(header)).isNull();

            MockHttpServletRequest get = refreshRequest();
            get.setMethod("GET");
            assertThat(converter.convert(get)).isNull();

            MockHttpServletRequest noClient = refreshRequest();
            noClient.removeParameter("client_id");
            assertThat(converter.convert(noClient)).isNull();

            MockHttpServletRequest pkce = refreshRequest();
            pkce.setParameter("code_verifier", "v");
            assertThat(converter.convert(pkce)).isNull();
        }

        @Test
        @DisplayName("a repeated client_id is an invalid request")
        void duplicateClientId() {
            MockHttpServletRequest request = refreshRequest();
            request.setParameter("client_id", new String[] {"a", "b"});
            assertThatThrownBy(() -> converter.convert(request)).isInstanceOf(OAuth2AuthenticationException.class);
        }
    }

    @Nested
    @DisplayName("provider")
    class ProviderCases {

        private final RegisteredClientRepository clients = mock(RegisteredClientRepository.class);
        private final PublicClientRefreshAuthentication.Provider provider = new PublicClientRefreshAuthentication.Provider(clients);
        private final RegisteredClient registered = RegisteredClients.publicClient("id", "abc", "App",
                List.of("http://localhost/cb"), Instant.EPOCH, new OAuthServerProperties());

        @Test
        @DisplayName("authenticates a known public client")
        void ok() {
            when(clients.findByClientId("abc")).thenReturn(registered);
            Authentication result = provider.authenticate(
                    new OAuth2ClientAuthenticationToken("abc", ClientAuthenticationMethod.NONE, null, Map.of()));
            assertThat(result.isAuthenticated()).isTrue();
            assertThat(((OAuth2ClientAuthenticationToken) result).getRegisteredClient()).isSameAs(registered);
        }

        @Test
        @DisplayName("unknown clients fail with invalid_client")
        void unknown() {
            when(clients.findByClientId("nope")).thenReturn(null);
            assertThatThrownBy(() -> provider.authenticate(
                    new OAuth2ClientAuthenticationToken("nope", ClientAuthenticationMethod.NONE, null, Map.of())))
                    .isInstanceOf(OAuth2AuthenticationException.class)
                    .satisfies(e -> assertThat(((OAuth2AuthenticationException) e).getError().getErrorCode())
                            .isEqualTo("invalid_client"));
        }

        @Test
        @DisplayName("leaves other methods and PKCE exchanges to the stock providers")
        void passes() {
            assertThat(provider.authenticate(new OAuth2ClientAuthenticationToken("abc",
                    ClientAuthenticationMethod.CLIENT_SECRET_BASIC, "s", Map.of()))).isNull();
            assertThat(provider.authenticate(new OAuth2ClientAuthenticationToken("abc",
                    ClientAuthenticationMethod.NONE, null, Map.of("code_verifier", "v")))).isNull();
            assertThat(provider.supports(OAuth2ClientAuthenticationToken.class)).isTrue();
        }
    }
}
