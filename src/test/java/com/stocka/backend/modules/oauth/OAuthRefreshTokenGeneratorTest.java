package com.stocka.backend.modules.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContext;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.DefaultOAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenContext;

import com.stocka.backend.modules.oauth.client.RegisteredClients;

@DisplayName("OAuthRefreshTokenGenerator")
class OAuthRefreshTokenGeneratorTest {

    private final Instant now = Instant.parse("2026-10-18T10:00:00Z");
    private final OAuthServerProperties props = new OAuthServerProperties();
    private final RegisteredClient client = RegisteredClients.publicClient("id", "c", "App",
            List.of("http://localhost/cb"), Instant.EPOCH, props);
    private final OAuthRefreshTokenGenerator generator = new OAuthRefreshTokenGenerator(props,
            Clock.fixed(now, ZoneOffset.UTC));

    private OAuth2TokenContext context(Set<String> scopes, Instant codeIssuedAt) {
        DefaultOAuth2TokenContext.Builder builder = DefaultOAuth2TokenContext.builder()
                .registeredClient(client)
                .principal(new TestingAuthenticationToken("1", null))
                .authorizationServerContext(new AuthorizationServerContext() {
                    @Override public String getIssuer() { return "http://localhost"; }
                    @Override public AuthorizationServerSettings getAuthorizationServerSettings() {
                        return AuthorizationServerSettings.builder().issuer("http://localhost").build();
                    }
                })
                .authorizedScopes(scopes)
                .tokenType(OAuth2TokenType.REFRESH_TOKEN)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrant(new TestingAuthenticationToken("c", null));
        if (codeIssuedAt != null) {
            OAuth2Authorization authorization = OAuth2Authorization.withRegisteredClient(client)
                    .principalName("1")
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .token(new OAuth2AuthorizationCode("code", codeIssuedAt, codeIssuedAt.plusSeconds(300)))
                    .build();
            builder.authorization(authorization);
        }
        return builder.build();
    }

    @Nested
    @DisplayName("issuance")
    class Issuance {

        @Test
        @DisplayName("issues a refresh token only when offline_access was granted")
        void offlineAccessRequired() {
            assertThat(generator.generate(context(Set.of("read"), now))).isNull();
            OAuth2RefreshToken token = generator.generate(context(Set.of("read", "offline_access"), now));
            assertThat(token).isNotNull();
            assertThat(token.getTokenValue()).hasSizeGreaterThan(60);
            assertThat(token.getExpiresAt()).isEqualTo(now.plus(props.getRefreshTokenTtl()));
        }

        @Test
        @DisplayName("ignores other token types")
        void otherTypes() {
            OAuth2TokenContext access = DefaultOAuth2TokenContext.builder()
                    .registeredClient(client)
                    .principal(new TestingAuthenticationToken("1", null))
                    .tokenType(OAuth2TokenType.ACCESS_TOKEN)
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .authorizationGrant(new TestingAuthenticationToken("c", null))
                    .build();
            assertThat(generator.generate(access)).isNull();
        }
    }

    @Nested
    @DisplayName("absolute lifetime")
    class Absolute {

        @Test
        @DisplayName("the expiry is capped at the code's issue time plus the absolute TTL")
        void capped() {
            props.setRefreshAbsoluteTtl(Duration.ofDays(90));
            Instant granted = now.minus(Duration.ofDays(89));
            OAuth2RefreshToken token = generator.generate(context(Set.of("offline_access"), granted));
            assertThat(token.getExpiresAt()).isEqualTo(granted.plus(Duration.ofDays(90)));
        }

        @Test
        @DisplayName("past the absolute TTL the grant is refused so the user re-authorizes")
        void expired() {
            props.setRefreshAbsoluteTtl(Duration.ofDays(90));
            Instant granted = now.minus(Duration.ofDays(91));
            assertThatThrownBy(() -> generator.generate(context(Set.of("offline_access"), granted)))
                    .isInstanceOf(OAuth2AuthenticationException.class)
                    .satisfies(e -> assertThat(((OAuth2AuthenticationException) e).getError().getErrorCode())
                            .isEqualTo("invalid_grant"));
        }

        @Test
        @DisplayName("without an authorization (first issuance) the sliding TTL applies")
        void noAuthorization() {
            OAuth2RefreshToken token = generator.generate(context(Set.of("offline_access"), null));
            assertThat(token.getExpiresAt()).isEqualTo(now.plus(props.getRefreshTokenTtl()));
        }
    }
}
