package com.stocka.backend.modules.oauth;

import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;

import org.springframework.security.crypto.keygen.Base64StringKeyGenerator;
import org.springframework.security.crypto.keygen.StringKeyGenerator;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;

/**
 * Issues refresh tokens only when {@code offline_access} was granted and caps their life to
 * {@code stocka.oauth.refresh-absolute-ttl} counted from the original authorization code.
 *
 * <p>Rotation gives every refresh a fresh sliding window; the absolute cap makes a stolen refresh
 * token useless after at most that period and forces the user through consent again.
 */
public class OAuthRefreshTokenGenerator implements OAuth2TokenGenerator<OAuth2RefreshToken> {

    private final StringKeyGenerator generator = new Base64StringKeyGenerator(Base64.getUrlEncoder().withoutPadding(), 96);
    private final OAuthServerProperties props;
    private final Clock clock;

    public OAuthRefreshTokenGenerator(OAuthServerProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
    }

    @Override
    public OAuth2RefreshToken generate(OAuth2TokenContext context) {
        if (!OAuth2TokenType.REFRESH_TOKEN.equals(context.getTokenType())) {
            return null;
        }
        Set<String> scopes = context.getAuthorizedScopes();
        if (scopes == null || !scopes.contains(OAuthScope.OFFLINE_ACCESS.value())) {
            return null;
        }
        Instant now = clock.instant();
        Instant expiresAt = now.plus(context.getRegisteredClient().getTokenSettings().getRefreshTokenTimeToLive());
        Instant absolute = absoluteLimit(context.getAuthorization());
        if (absolute != null) {
            if (!absolute.isAfter(now)) {
                throw new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_GRANT,
                        "The authorization is too old; the user must authorize again", null));
            }
            if (absolute.isBefore(expiresAt)) {
                expiresAt = absolute;
            }
        }
        return new OAuth2RefreshToken(generator.generateKey(), now, expiresAt);
    }

    private Instant absoluteLimit(OAuth2Authorization authorization) {
        if (authorization == null) return null;
        OAuth2Authorization.Token<OAuth2AuthorizationCode> code = authorization.getToken(OAuth2AuthorizationCode.class);
        if (code == null || code.getToken().getIssuedAt() == null) return null;
        return code.getToken().getIssuedAt().plus(props.getRefreshAbsoluteTtl());
    }
}
