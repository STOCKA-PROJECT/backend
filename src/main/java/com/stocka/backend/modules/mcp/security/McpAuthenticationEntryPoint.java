package com.stocka.backend.modules.mcp.security;

import java.io.IOException;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import com.stocka.backend.modules.common.error.ErrorCodes;
import com.stocka.backend.modules.common.error.ProblemDetailFactory;
import com.stocka.backend.modules.oauth.OAuthScope;
import com.stocka.backend.modules.oauth.OAuthServerProperties;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code 401}/{@code 403} responses of the MCP endpoint, shaped the way MCP clients discover
 * the authorization server: {@code WWW-Authenticate: Bearer resource_metadata="…"} pointing at the
 * protected resource metadata, plus the scopes a client should request. The body is the usual
 * problem+json of the API.
 */
@Component
public class McpAuthenticationEntryPoint implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final OAuthServerProperties oauth;
    private final ProblemDetailFactory problems;
    private final JsonMapper json;

    public McpAuthenticationEntryPoint(OAuthServerProperties oauth, ProblemDetailFactory problems, JsonMapper json) {
        this.oauth = oauth;
        this.problems = problems;
        this.json = json;
    }

    /** @return URL of the protected resource metadata document of the MCP endpoint */
    public String resourceMetadataUrl() {
        return oauth.issuerOrDefault() + McpSecurityConfiguration.METADATA_PATH + OAuthServerProperties.DEFAULT_RESOURCE_PATH;
    }

    /** @return the scopes advertised in the {@code 401} challenge */
    public static String defaultScopes() {
        return String.join(" ", OAuthScope.READ.value(), OAuthScope.INVENTORY_WRITE.value(),
                OAuthScope.TIMELINES_WRITE.value());
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException exception) throws IOException {
        StringBuilder challenge = new StringBuilder("Bearer resource_metadata=\"")
                .append(resourceMetadataUrl()).append("\", scope=\"").append(defaultScopes()).append('"');
        boolean presented = request.getHeader(HttpHeaders.AUTHORIZATION) != null;
        if (exception instanceof OAuth2AuthenticationException oauthEx && presented) {
            challenge.append(", error=\"invalid_token\"");
            String description = oauthEx.getError().getDescription();
            if (description != null && !description.isBlank()) {
                challenge.append(", error_description=\"").append(description.replace("\"", "'")).append('"');
            }
        }
        write(request, response, HttpStatus.UNAUTHORIZED, challenge.toString(),
                presented ? ErrorCodes.AUTH_UNAUTHENTICATED : ErrorCodes.AUTH_UNAUTHENTICATED, Map.of());
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        String challenge = "Bearer error=\"insufficient_scope\", scope=\"" + defaultScopes()
                + "\", resource_metadata=\"" + resourceMetadataUrl() + "\"";
        write(request, response, HttpStatus.FORBIDDEN, challenge, ErrorCodes.AUTH_FORBIDDEN, Map.of());
    }

    private void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status,
                       String challenge, String code, Map<String, Object> params) throws IOException {
        ProblemDetail body = problems.build(status, code, params, request.getRequestURI());
        response.setStatus(status.value());
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, challenge);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(json.writeValueAsString(body));
    }
}
