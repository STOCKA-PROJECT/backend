package com.stocka.backend.modules.mcp.security;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Component;

import com.stocka.backend.modules.oauth.OAuthScope;
import com.stocka.backend.modules.security.service.JwtService;
import com.stocka.backend.modules.users.entity.User;
import com.stocka.backend.modules.users.repository.UserRepository;

/**
 * Turns a validated access token into an {@link McpAuthentication}.
 *
 * <p>Beyond signature, issuer, audience and expiry (checked by the decoder) a token is accepted only
 * when: its {@code tokenVersion} is the current one; the user still exists; the password did not
 * change after the token was issued; and its authorization row still exists and is not invalidated
 * (that is what makes revocation from "connected apps" immediate).
 */
@Component
public class McpAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final UserRepository users;
    private final JwtService jwtService;
    private final OAuth2AuthorizationService authorizations;

    public McpAuthenticationConverter(UserRepository users, JwtService jwtService,
                                      OAuth2AuthorizationService authorizations) {
        this.users = users;
        this.jwtService = jwtService;
        this.authorizations = authorizations;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Integer version = jwt.getClaim(JwtService.CLAIM_TOKEN_VERSION) instanceof Number n ? n.intValue() : null;
        if (version == null || version != jwtService.getCurrentTokenVersion()) {
            throw invalid("The token was issued before a global sign-out");
        }
        Integer userId = parseId(jwt.getSubject());
        if (userId == null) {
            throw invalid("The token has no usable subject");
        }
        User user = users.findById(userId).filter(u -> u.getDeletedAt() == null)
                .orElseThrow(() -> invalid("The user no longer exists"));
        if (issuedBeforePasswordChange(jwt.getIssuedAt(), user)) {
            throw invalid("The password changed after the token was issued");
        }
        OAuth2Authorization authorization = authorizations.findByToken(jwt.getTokenValue(), OAuth2TokenType.ACCESS_TOKEN);
        if (authorization == null || authorization.getAccessToken() == null
                || authorization.getAccessToken().isInvalidated()) {
            throw invalid("The token was revoked");
        }
        Set<OAuthScope> scopes = OAuthScope.parse(scopeClaim(jwt));
        String clientName = jwt.getClaimAsString("client_name");
        return new McpAuthentication(user, scopes, clientName, jwt.getId());
    }

    private static List<String> scopeClaim(Jwt jwt) {
        Object raw = jwt.getClaim("scope");
        if (raw instanceof String s) {
            return List.of(s.split("\\s+"));
        }
        List<String> list = jwt.getClaimAsStringList("scope");
        return list == null ? List.of() : list;
    }

    private static boolean issuedBeforePasswordChange(Instant issuedAt, User user) {
        LocalDateTime changed = user.getPasswordChangedAt();
        if (changed == null || issuedAt == null) return false;
        return issuedAt.isBefore(changed.toInstant(ZoneOffset.UTC));
    }

    private static Integer parseId(String subject) {
        if (subject == null) return null;
        try {
            return Integer.valueOf(subject.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static InvalidBearerTokenException invalid(String description) {
        return new InvalidBearerTokenException(description);
    }

    static String audience(Jwt jwt) {
        List<String> aud = jwt.getClaimAsStringList(JwtClaimNames.AUD);
        return aud == null || aud.isEmpty() ? null : aud.get(0);
    }
}
