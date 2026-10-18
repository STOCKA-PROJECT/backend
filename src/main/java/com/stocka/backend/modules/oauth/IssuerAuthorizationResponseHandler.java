package com.stocka.backend.modules.oauth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Sends the authorization code back to the client with the {@code iss} parameter (RFC 9207), so
 * clients can detect mix-up attacks. Mirrors the default redirect of Spring Authorization Server
 * otherwise ({@code code} and {@code state}).
 */
public class IssuerAuthorizationResponseHandler implements AuthenticationSuccessHandler {

    private final RedirectStrategy redirectStrategy = new DefaultRedirectStrategy();
    private final OAuthServerProperties props;

    public IssuerAuthorizationResponseHandler(OAuthServerProperties props) {
        this.props = props;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        OAuth2AuthorizationCodeRequestAuthenticationToken token =
                (OAuth2AuthorizationCodeRequestAuthenticationToken) authentication;
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(token.getRedirectUri())
                .queryParam(OAuth2ParameterNames.CODE, token.getAuthorizationCode().getTokenValue());
        if (StringUtils.hasText(token.getState())) {
            builder.queryParam(OAuth2ParameterNames.STATE, UriUtils.encode(token.getState(), StandardCharsets.UTF_8));
        }
        builder.queryParam("iss", UriUtils.encode(props.issuerOrDefault(), StandardCharsets.UTF_8));
        redirectStrategy.sendRedirect(request, response, builder.build(true).toUriString());
    }
}
