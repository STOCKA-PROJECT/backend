package com.stocka.backend.modules.oauth;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2RefreshTokenAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;

import com.stocka.backend.modules.security.audit.SecurityAuditService;
import com.stocka.backend.modules.security.audit.SecurityEventType;
import com.stocka.backend.modules.security.service.JwtService;
import com.stocka.backend.modules.users.entity.User;
import com.stocka.backend.modules.users.repository.UserRepository;

/**
 * Shapes the access tokens issued to MCP connectors.
 *
 * <p>Adds the audience (the MCP endpoint), the user's email and display name, the global
 * {@code tokenVersion} (so the logout-everyone switch of {@code JWT_TOKEN_VERSION} also covers
 * these tokens) and the client name; enforces the RFC 8707 {@code resource} indicator of the token
 * request; refuses to refresh when the password changed after the grant; and records the first
 * issuance of an authorization as {@code OAUTH_APP_AUTHORIZED} in the security audit.
 */
public class OAuthTokenCustomizer implements OAuth2TokenCustomizer<JwtEncodingContext> {

    private static final Logger log = LoggerFactory.getLogger(OAuthTokenCustomizer.class);

    private final OAuthServerProperties props;
    private final UserRepository users;
    private final JwtService jwtService;
    private final SecurityAuditService audit;

    public OAuthTokenCustomizer(OAuthServerProperties props, UserRepository users, JwtService jwtService,
                                SecurityAuditService audit) {
        this.props = props;
        this.users = users;
        this.jwtService = jwtService;
        this.audit = audit;
    }

    @Override
    public void customize(JwtEncodingContext context) {
        if (!OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
            return;
        }
        validateResource(context);
        User user = resolveUser(context.getPrincipal());
        if (context.getAuthorizationGrantType().equals(AuthorizationGrantType.REFRESH_TOKEN)) {
            rejectIfPasswordChanged(user, context.getAuthorization());
        }
        Set<String> scopes = context.getAuthorizedScopes();
        context.getClaims()
                .subject(String.valueOf(user.getId()))
                .audience(new ArrayList<>(List.of(props.resourceUrlOrDefault())))
                .id(UUID.randomUUID().toString())
                .claim("email", user.getEmail())
                .claim("name", displayName(user))
                .claim(JwtService.CLAIM_TOKEN_VERSION, jwtService.getCurrentTokenVersion())
                .claim("client_name", context.getRegisteredClient().getClientName());
        if (context.getAuthorizationGrantType().equals(AuthorizationGrantType.AUTHORIZATION_CODE)) {
            audit.recordSuccess(SecurityEventType.OAUTH_APP_AUTHORIZED, user,
                    "{\"client\":\"" + escape(context.getRegisteredClient().getClientName())
                            + "\",\"scopes\":\"" + String.join(" ", scopes) + "\"}");
            log.info("oauth_app_authorized user={} client={} scopes={}", user.getId(),
                    context.getRegisteredClient().getClientId(), scopes);
        }
    }

    private User resolveUser(Authentication principal) {
        String name = principal == null ? null : principal.getName();
        Integer id = parseId(name);
        if (id == null) {
            throw invalidGrant("Unknown subject");
        }
        return users.findById(id)
                .filter(u -> u.getDeletedAt() == null)
                .orElseThrow(() -> invalidGrant("The user no longer exists"));
    }

    private void rejectIfPasswordChanged(User user, OAuth2Authorization authorization) {
        if (user.getPasswordChangedAt() == null || authorization == null) return;
        OAuth2Authorization.Token<OAuth2AuthorizationCode> code = authorization.getToken(OAuth2AuthorizationCode.class);
        Instant grantedAt = code == null || code.getToken().getIssuedAt() == null ? null : code.getToken().getIssuedAt();
        if (grantedAt == null) return;
        LocalDateTime changed = user.getPasswordChangedAt();
        if (changed.toInstant(ZoneOffset.UTC).isAfter(grantedAt)) {
            throw invalidGrant("The password changed after the authorization was granted");
        }
    }

    private void validateResource(JwtEncodingContext context) {
        String canonical = props.resourceUrlOrDefault();
        Authentication grant = context.getAuthorizationGrant();
        Map<String, Object> params = null;
        if (grant instanceof OAuth2AuthorizationCodeAuthenticationToken code) {
            params = code.getAdditionalParameters();
        } else if (grant instanceof OAuth2RefreshTokenAuthenticationToken refresh) {
            params = refresh.getAdditionalParameters();
        }
        check(params == null ? null : params.get("resource"), canonical);
        OAuth2Authorization authorization = context.getAuthorization();
        if (authorization != null) {
            OAuth2AuthorizationRequest request = authorization.getAttribute(OAuth2AuthorizationRequest.class.getName());
            if (request != null && request.getAdditionalParameters() != null) {
                check(request.getAdditionalParameters().get("resource"), canonical);
            }
        }
    }

    private static void check(Object resource, String canonical) {
        if (resource == null) return;
        String value = resource instanceof String[] arr && arr.length > 0 ? arr[0] : String.valueOf(resource);
        if (!OAuthRedirectUriValidator.matchesResource(value, canonical)) {
            throw new OAuth2AuthenticationException(new OAuth2Error(OAuthRedirectUriValidator.INVALID_TARGET,
                    "The resource parameter must be " + canonical, null));
        }
    }

    static Integer parseId(String name) {
        if (name == null) return null;
        try {
            return Integer.valueOf(name.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String displayName(User user) {
        String name = user.getName() == null ? "" : user.getName().trim();
        String last = user.getLastName() == null ? "" : user.getLastName().trim();
        String full = (name + " " + last).trim();
        return full.isEmpty() ? user.getEmail() : full;
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static OAuth2AuthenticationException invalidGrant(String description) {
        return new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_GRANT, description, null));
    }
}
