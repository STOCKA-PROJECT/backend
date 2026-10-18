package com.stocka.backend.modules.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;

import com.stocka.backend.modules.oauth.client.RegisteredClients;
import com.stocka.backend.modules.security.audit.SecurityAuditService;
import com.stocka.backend.modules.security.audit.SecurityEventType;
import com.stocka.backend.modules.security.service.JwtService;
import com.stocka.backend.modules.users.entity.User;
import com.stocka.backend.modules.users.repository.UserRepository;

@DisplayName("OAuthTokenCustomizer")
class OAuthTokenCustomizerTest {

    private final OAuthServerProperties props = new OAuthServerProperties();
    private final UserRepository users = mock(UserRepository.class);
    private final JwtService jwtService = mock(JwtService.class);
    private final SecurityAuditService audit = mock(SecurityAuditService.class);
    private final OAuthTokenCustomizer customizer = new OAuthTokenCustomizer(props, users, jwtService, audit);
    private final RegisteredClient client = RegisteredClients.publicClient("id", "c", "Claude",
            List.of("http://localhost/cb"), Instant.EPOCH, props);
    private final Instant now = Instant.parse("2026-10-18T10:00:00Z");
    private User user;

    @BeforeEach
    void setUp() {
        props.setIssuer("http://localhost");
        user = new User().setEmail("joan@x").setName("Joan").setLastName("Martorell");
        user.setId(5);
        when(users.findById(5)).thenReturn(Optional.of(user));
        when(jwtService.getCurrentTokenVersion()).thenReturn(3);
    }

    private JwtEncodingContext context(AuthorizationGrantType grant, Map<String, Object> grantParams,
                                       Map<String, Object> authorizeParams, OAuth2TokenType tokenType) {
        Authentication principal = new TestingAuthenticationToken("5", null);
        OAuth2AuthorizationRequest request = OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri("http://localhost/oauth2/authorize").clientId("c").redirectUri("http://localhost/cb")
                .additionalParameters(authorizeParams).build();
        OAuth2Authorization authorization = OAuth2Authorization.withRegisteredClient(client)
                .principalName("5")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .attribute(OAuth2AuthorizationRequest.class.getName(), request)
                .token(new OAuth2AuthorizationCode("code", now, now.plusSeconds(300)))
                .build();
        Authentication grantAuth = new OAuth2AuthorizationCodeAuthenticationToken("code", principal, "http://localhost/cb", grantParams);
        return JwtEncodingContext.with(JwsHeader.with(SignatureAlgorithm.RS256), JwtClaimsSet.builder().subject("5"))
                .registeredClient(client)
                .principal(principal)
                .authorization(authorization)
                .authorizedScopes(Set.of("read", "offline_access"))
                .tokenType(tokenType)
                .authorizationGrantType(grant)
                .authorizationGrant(grantAuth)
                .build();
    }

    @Nested
    @DisplayName("claims")
    class Claims {

        @Test
        @DisplayName("audience, subject, email, name, token version and client name are set; the grant is audited")
        void claims() {
            JwtEncodingContext ctx = context(AuthorizationGrantType.AUTHORIZATION_CODE, Map.of(), Map.of(), OAuth2TokenType.ACCESS_TOKEN);
            customizer.customize(ctx);
            JwtClaimsSet claims = ctx.getClaims().build();
            assertThat(claims.getAudience()).containsExactly("http://localhost/mcp");
            assertThat(claims.getSubject()).isEqualTo("5");
            assertThat(claims.getId()).isNotBlank();
            assertThat(claims.<String>getClaim("email")).isEqualTo("joan@x");
            assertThat(claims.<String>getClaim("name")).isEqualTo("Joan Martorell");
            assertThat(claims.<Integer>getClaim(JwtService.CLAIM_TOKEN_VERSION)).isEqualTo(3);
            assertThat(claims.<String>getClaim("client_name")).isEqualTo("Claude");
            verify(audit).recordSuccess(eq(SecurityEventType.OAUTH_APP_AUTHORIZED), eq(user), any());
        }

        @Test
        @DisplayName("refresh grants are not audited again and other token types are untouched")
        void refresh() {
            JwtEncodingContext ctx = context(AuthorizationGrantType.REFRESH_TOKEN, Map.of(), Map.of(), OAuth2TokenType.ACCESS_TOKEN);
            customizer.customize(ctx);
            verify(audit, never()).recordSuccess(any(), any(), any());
            JwtEncodingContext other = context(AuthorizationGrantType.AUTHORIZATION_CODE, Map.of(), Map.of(), new OAuth2TokenType("id_token"));
            customizer.customize(other);
            assertThat(other.getClaims().build().getAudience()).isNull();
        }
    }

    @Nested
    @DisplayName("refusals")
    class Refusals {

        @Test
        @DisplayName("a resource other than the MCP endpoint, in the token or authorize request, is invalid_target")
        void resource() {
            assertThatThrownBy(() -> customizer.customize(context(AuthorizationGrantType.AUTHORIZATION_CODE,
                    Map.of("resource", "https://evil.example/mcp"), Map.of(), OAuth2TokenType.ACCESS_TOKEN)))
                    .isInstanceOf(OAuth2AuthenticationException.class)
                    .satisfies(e -> assertThat(((OAuth2AuthenticationException) e).getError().getErrorCode()).isEqualTo("invalid_target"));
            assertThatThrownBy(() -> customizer.customize(context(AuthorizationGrantType.AUTHORIZATION_CODE,
                    Map.of(), Map.of("resource", "https://evil.example/mcp"), OAuth2TokenType.ACCESS_TOKEN)))
                    .isInstanceOf(OAuth2AuthenticationException.class);
            customizer.customize(context(AuthorizationGrantType.AUTHORIZATION_CODE,
                    Map.of("resource", "http://localhost/mcp/"), Map.of(), OAuth2TokenType.ACCESS_TOKEN));
        }

        @Test
        @DisplayName("a vanished user or a non-numeric subject is invalid_grant")
        void user() {
            when(users.findById(5)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> customizer.customize(context(AuthorizationGrantType.AUTHORIZATION_CODE, Map.of(), Map.of(),
                    OAuth2TokenType.ACCESS_TOKEN)))
                    .satisfies(e -> assertThat(((OAuth2AuthenticationException) e).getError().getErrorCode()).isEqualTo("invalid_grant"));
        }

        @Test
        @DisplayName("refreshing after a password change is refused")
        void passwordChanged() {
            user.setPasswordChangedAt(LocalDateTime.of(2026, 10, 18, 12, 0));
            assertThatThrownBy(() -> customizer.customize(context(AuthorizationGrantType.REFRESH_TOKEN, Map.of(), Map.of(),
                    OAuth2TokenType.ACCESS_TOKEN)))
                    .satisfies(e -> assertThat(((OAuth2AuthenticationException) e).getError().getDescription()).contains("password"));
            user.setPasswordChangedAt(LocalDateTime.of(2026, 10, 18, 9, 0));
            customizer.customize(context(AuthorizationGrantType.REFRESH_TOKEN, Map.of(), Map.of(), OAuth2TokenType.ACCESS_TOKEN));
        }
    }
}
