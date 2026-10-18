package com.stocka.backend.modules.oauth;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.function.Consumer;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationException;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.util.StringUtils;

import com.stocka.backend.modules.oauth.client.RedirectUris;

/**
 * Validates the {@code redirect_uri} and {@code resource} parameters of an authorization request.
 *
 * <p>Replaces Spring Authorization Server's default redirect validator so that native MCP clients may
 * redirect to {@code http://localhost:<any port>/...} (the default rejects the {@code localhost}
 * name). Everything else keeps exact matching. The {@code resource} indicator (RFC 8707), when sent,
 * must be the MCP endpoint of this server; a mismatch is reported back to the client as
 * {@code invalid_target}.
 */
public class OAuthRedirectUriValidator implements Consumer<OAuth2AuthorizationCodeRequestAuthenticationContext> {

    static final String ERROR_URI = "https://datatracker.ietf.org/doc/html/rfc6749#section-4.1.2.1";
    static final String INVALID_TARGET = "invalid_target";

    private final OAuthServerProperties props;

    public OAuthRedirectUriValidator(OAuthServerProperties props) {
        this.props = props;
    }

    @Override
    public void accept(OAuth2AuthorizationCodeRequestAuthenticationContext context) {
        OAuth2AuthorizationCodeRequestAuthenticationToken token = context.getAuthentication();
        RegisteredClient client = context.getRegisteredClient();
        String requested = token.getRedirectUri();

        if (StringUtils.hasText(requested)) {
            URI uri = parse(requested);
            if (uri.getFragment() != null || uri.getHost() == null) {
                rejectRedirect();
            }
            boolean ok = client.getRedirectUris().stream().anyMatch(r -> RedirectUris.matches(r, requested));
            if (!ok) {
                rejectRedirect();
            }
        } else if (client.getRedirectUris().size() != 1) {
            // Without redirect_uri the client must have exactly one registered.
            rejectRedirect();
        }

        validateResource(token);
    }

    private void validateResource(OAuth2AuthorizationCodeRequestAuthenticationToken token) {
        Map<String, Object> extra = token.getAdditionalParameters();
        Object resource = extra == null ? null : extra.get("resource");
        if (resource == null) return;
        String value = resource instanceof String[] arr && arr.length > 0 ? arr[0] : String.valueOf(resource);
        if (!matchesResource(value, props.resourceUrlOrDefault())) {
            OAuth2Error error = new OAuth2Error(INVALID_TARGET,
                    "The resource parameter must be " + props.resourceUrlOrDefault(), ERROR_URI);
            // The redirect URI was validated above, so the error can travel back to the client.
            throw new OAuth2AuthorizationCodeRequestAuthenticationException(error, token);
        }
    }

    /** @return whether {@code requested} names the same resource as {@code canonical} */
    public static boolean matchesResource(String requested, String canonical) {
        if (requested == null || requested.isBlank()) return false;
        return normalize(requested).equals(normalize(canonical));
    }

    static String normalize(String url) {
        String v = OAuthServerProperties.stripTrailingSlash(url);
        try {
            URI u = new URI(v);
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
            String host = u.getHost() == null ? "" : u.getHost().toLowerCase();
            String port = u.getPort() == -1 ? "" : ":" + u.getPort();
            String path = u.getRawPath() == null ? "" : u.getRawPath();
            return scheme + "://" + host + port + path;
        } catch (URISyntaxException e) {
            return v;
        }
    }

    private static URI parse(String value) {
        try {
            return new URI(value);
        } catch (URISyntaxException e) {
            rejectRedirect();
            return null; // unreachable
        }
    }

    private static void rejectRedirect() {
        OAuth2Error error = new OAuth2Error(OAuth2ErrorCodes.INVALID_REQUEST,
                "OAuth 2.0 Parameter: redirect_uri", ERROR_URI);
        // No authentication: the server must not redirect to an unverified URI.
        throw new OAuth2AuthorizationCodeRequestAuthenticationException(error, null);
    }
}
