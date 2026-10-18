package com.stocka.backend.modules.mcp.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

import com.stocka.backend.modules.oauth.OAuthScope;
import com.stocka.backend.modules.oauth.OAuthServerProperties;
import com.stocka.backend.modules.oauth.client.RegisteredClients;
import com.stocka.backend.modules.roles.entity.Role;
import com.stocka.backend.modules.roles.entity.RoleEnum;
import com.stocka.backend.modules.security.service.JwtService;
import com.stocka.backend.modules.users.entity.User;
import com.stocka.backend.modules.users.repository.UserRepository;

@DisplayName("McpAuthenticationConverter")
class McpAuthenticationConverterTest {

    private final UserRepository users = mock(UserRepository.class);
    private final JwtService jwtService = mock(JwtService.class);
    private final OAuth2AuthorizationService authorizations = mock(OAuth2AuthorizationService.class);
    private final McpAuthenticationConverter converter = new McpAuthenticationConverter(users, jwtService, authorizations);
    private final Instant now = Instant.parse("2026-10-18T10:00:00Z");
    private User user;

    @BeforeEach
    void setUp() {
        user = new User().setEmail("joan@x").setRole(new Role().setName(RoleEnum.USER));
        user.setId(7);
        when(jwtService.getCurrentTokenVersion()).thenReturn(1);
        when(users.findById(7)).thenReturn(Optional.of(user));
        RegisteredClient client = RegisteredClients.publicClient("id", "c", "Claude", List.of("http://localhost/cb"),
                Instant.EPOCH, new OAuthServerProperties());
        OAuth2Authorization authorization = OAuth2Authorization.withRegisteredClient(client)
                .principalName("7")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .token(new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "tok", now, now.plusSeconds(600)))
                .build();
        when(authorizations.findByToken(eq("tok"), any(OAuth2TokenType.class))).thenReturn(authorization);
    }

    private Jwt jwt(Object version, String subject, Instant issuedAt) {
        Jwt.Builder builder = Jwt.withTokenValue("tok").header("alg", "RS256").subject(subject)
                .issuedAt(issuedAt).expiresAt(issuedAt.plusSeconds(600))
                .claim("scope", List.of("read", "delete"))
                .claim("client_name", "Claude")
                .claim("jti", "jti-1");
        if (version != null) builder.claim(JwtService.CLAIM_TOKEN_VERSION, version);
        return builder.build();
    }

    @Nested
    @DisplayName("accepts")
    class Accepts {

        @Test
        @DisplayName("a live token of an existing user with the current version")
        void ok() {
            McpAuthentication auth = (McpAuthentication) converter.convert(jwt(1, "7", now));
            assertThat(auth.isAuthenticated()).isTrue();
            assertThat(auth.getUser()).isSameAs(user);
            assertThat(auth.getName()).isEqualTo("7");
            assertThat(auth.getScopes()).containsExactly(OAuthScope.READ, OAuthScope.DELETE);
            assertThat(auth.getClientName()).isEqualTo("Claude");
            assertThat(auth.getTokenId()).isEqualTo("jti-1");
            assertThat(auth.getAuthorities()).extracting(a -> a.getAuthority()).contains("ROLE_USER", "SCOPE_read", "SCOPE_delete");
            assertThat(auth.hasScope(OAuthScope.READ)).isTrue();
            assertThat(auth.hasScope(OAuthScope.INVENTORY_WRITE)).isFalse();
        }

        @Test
        @DisplayName("a space-separated scope claim")
        void scopeString() {
            Jwt jwt = Jwt.withTokenValue("tok").header("alg", "RS256").subject("7").issuedAt(now).expiresAt(now.plusSeconds(10))
                    .claim("scope", "read inventory:write").claim(JwtService.CLAIM_TOKEN_VERSION, 1).build();
            McpAuthentication auth = (McpAuthentication) converter.convert(jwt);
            assertThat(auth.getScopes()).containsExactly(OAuthScope.READ, OAuthScope.INVENTORY_WRITE);
        }
    }

    @Nested
    @DisplayName("rejects")
    class Rejects {

        @Test
        @DisplayName("a stale or missing token version")
        void version() {
            assertThatThrownBy(() -> converter.convert(jwt(0, "7", now))).isInstanceOf(InvalidBearerTokenException.class);
            assertThatThrownBy(() -> converter.convert(jwt(null, "7", now))).isInstanceOf(InvalidBearerTokenException.class);
        }

        @Test
        @DisplayName("an unknown, deleted or malformed subject")
        void subject() {
            when(users.findById(8)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> converter.convert(jwt(1, "8", now))).isInstanceOf(InvalidBearerTokenException.class);
            assertThatThrownBy(() -> converter.convert(jwt(1, "abc", now))).isInstanceOf(InvalidBearerTokenException.class);
            user.setDeletedAt(LocalDateTime.now());
            assertThatThrownBy(() -> converter.convert(jwt(1, "7", now))).hasMessageContaining("no longer exists");
        }

        @Test
        @DisplayName("a token issued before the password changed")
        void passwordChanged() {
            user.setPasswordChangedAt(LocalDateTime.of(2026, 10, 18, 11, 0));
            assertThatThrownBy(() -> converter.convert(jwt(1, "7", now))).hasMessageContaining("password");
            user.setPasswordChangedAt(LocalDateTime.of(2026, 10, 18, 9, 0));
            assertThat(converter.convert(jwt(1, "7", now))).isNotNull();
        }

        @Test
        @DisplayName("a token whose authorization was revoked")
        void revoked() {
            when(authorizations.findByToken(eq("tok"), any(OAuth2TokenType.class))).thenReturn(null);
            assertThatThrownBy(() -> converter.convert(jwt(1, "7", now))).hasMessageContaining("revoked");
        }
    }
}
