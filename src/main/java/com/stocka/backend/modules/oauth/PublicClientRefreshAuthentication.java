package com.stocka.backend.modules.oauth;

import java.util.HashMap;
import java.util.Map;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.core.endpoint.PkceParameterNames;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.web.authentication.AuthenticationConverter;
import org.springframework.util.StringUtils;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Client authentication for public clients on the refresh-token grant and the revocation endpoint.
 *
 * <p>Spring Authorization Server authenticates public clients only on the PKCE code exchange
 * ({@code grant_type=authorization_code} + {@code code_verifier}); a public client refreshing or
 * revoking a token presents just its {@code client_id}, which the stock converters ignore and the
 * token endpoint then rejects with {@code invalid_client}. OAuth 2.1 allows this (the rotated refresh
 * token is the credential), and MCP clients such as Claude depend on it. This converter claims the
 * request when it carries a {@code client_id}, no secret, no {@code code_verifier} and is either a
 * {@code refresh_token} grant or a revocation; the provider checks the client is registered as public.
 */
public final class PublicClientRefreshAuthentication {

    private PublicClientRefreshAuthentication() {
    }

    /** Builds the unauthenticated client token from the request. */
    public static final class Converter implements AuthenticationConverter {
        @Override
        public Authentication convert(HttpServletRequest request) {
            if (!"POST".equalsIgnoreCase(request.getMethod())) return null;
            if (StringUtils.hasText(request.getHeader("Authorization"))) return null;
            String grantType = request.getParameter(OAuth2ParameterNames.GRANT_TYPE);
            boolean revocation = request.getRequestURI() != null && request.getRequestURI().endsWith("/oauth2/revoke");
            if (!revocation && !AuthorizationGrantType.REFRESH_TOKEN.getValue().equals(grantType)) return null;
            String clientId = request.getParameter(OAuth2ParameterNames.CLIENT_ID);
            if (!StringUtils.hasText(clientId)) return null;
            if (StringUtils.hasText(request.getParameter(OAuth2ParameterNames.CLIENT_SECRET))) return null;
            if (StringUtils.hasText(request.getParameter(PkceParameterNames.CODE_VERIFIER))) return null;
            String[] ids = request.getParameterValues(OAuth2ParameterNames.CLIENT_ID);
            if (ids != null && ids.length != 1) {
                throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_REQUEST);
            }
            Map<String, Object> additional = new HashMap<>();
            request.getParameterMap().forEach((key, values) -> {
                if (!OAuth2ParameterNames.CLIENT_ID.equals(key)) {
                    additional.put(key, values.length == 1 ? values[0] : values);
                }
            });
            return new OAuth2ClientAuthenticationToken(clientId, ClientAuthenticationMethod.NONE, null, additional);
        }
    }

    /** Authenticates the client token when the registered client is public. */
    public static final class Provider implements AuthenticationProvider {
        private final RegisteredClientRepository clients;

        public Provider(RegisteredClientRepository clients) {
            this.clients = clients;
        }

        @Override
        public Authentication authenticate(Authentication authentication) throws AuthenticationException {
            OAuth2ClientAuthenticationToken token = (OAuth2ClientAuthenticationToken) authentication;
            if (!ClientAuthenticationMethod.NONE.equals(token.getClientAuthenticationMethod())) return null;
            Map<String, Object> params = token.getAdditionalParameters();
            if (params != null && params.containsKey(PkceParameterNames.CODE_VERIFIER)) {
                return null; // the stock PKCE provider owns that case
            }
            RegisteredClient client = clients.findByClientId(token.getPrincipal().toString());
            if (client == null || !client.getClientAuthenticationMethods().contains(ClientAuthenticationMethod.NONE)) {
                throw new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_CLIENT,
                        "Client authentication failed: client_id", null));
            }
            return new OAuth2ClientAuthenticationToken(client, ClientAuthenticationMethod.NONE, null);
        }

        @Override
        public boolean supports(Class<?> authentication) {
            return OAuth2ClientAuthenticationToken.class.isAssignableFrom(authentication);
        }
    }
}
