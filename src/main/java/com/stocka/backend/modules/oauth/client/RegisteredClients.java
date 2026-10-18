package com.stocka.backend.modules.oauth.client;

import java.time.Instant;
import java.util.Collection;

import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;

import com.stocka.backend.modules.oauth.OAuthScope;
import com.stocka.backend.modules.oauth.OAuthServerProperties;

/**
 * Builds {@link RegisteredClient}s with Stocka's single client profile: public client, PKCE
 * mandatory, authorization code + refresh token, consent required, every scope allowed (the consent
 * page is where the user narrows them).
 */
public final class RegisteredClients {

    private RegisteredClients() {
    }

    /**
     * @param id           storage id (at most 100 characters)
     * @param clientId     wire client id (random string or CIMD URL)
     * @param clientName   display name shown on the consent page
     * @param redirectUris allowed redirect URIs
     * @param issuedAt     registration time
     * @param props        TTLs
     * @return the client
     */
    public static RegisteredClient publicClient(String id, String clientId, String clientName,
                                                Collection<String> redirectUris, Instant issuedAt,
                                                OAuthServerProperties props) {
        RegisteredClient.Builder builder = RegisteredClient.withId(id)
                .clientId(clientId)
                .clientIdIssuedAt(issuedAt)
                .clientName(clientName)
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(true)
                        .requireAuthorizationConsent(true)
                        .build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenFormat(OAuth2TokenFormat.SELF_CONTAINED)
                        .accessTokenTimeToLive(props.getAccessTokenTtl())
                        .refreshTokenTimeToLive(props.getRefreshTokenTtl())
                        .reuseRefreshTokens(false)
                        .authorizationCodeTimeToLive(props.getAuthorizationCodeTtl())
                        .build());
        for (String uri : redirectUris) {
            builder.redirectUri(uri);
        }
        for (String scope : OAuthScope.allValues()) {
            builder.scope(scope);
        }
        return builder.build();
    }
}
