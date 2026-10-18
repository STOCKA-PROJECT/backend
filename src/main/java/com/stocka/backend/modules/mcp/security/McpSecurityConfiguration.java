package com.stocka.backend.modules.mcp.security;

import java.util.List;
import java.util.function.Consumer;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.OAuth2ProtectedResourceMetadata;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.stocka.backend.modules.mcp.McpProperties;
import com.stocka.backend.modules.oauth.OAuthScope;
import com.stocka.backend.modules.oauth.OAuthServerProperties;

/**
 * Security of the MCP endpoint ({@code @Order(2)}): an OAuth 2.1 resource server that accepts only
 * the access tokens minted by the embedded authorization server for the {@code /mcp} audience.
 * The protected resource metadata is public.
 */
@Configuration
@EnableConfigurationProperties(McpProperties.class)
@ConditionalOnProperty(prefix = "stocka.mcp", name = "enabled", havingValue = "true", matchIfMissing = true)
public class McpSecurityConfiguration {

    /** Path of the MCP endpoint. */
    public static final String MCP_PATH = OAuthServerProperties.DEFAULT_RESOURCE_PATH;
    /** Root of the protected resource metadata (RFC 9728); Spring Security also serves the {@code /mcp} suffix. */
    public static final String METADATA_PATH = "/.well-known/oauth-protected-resource";

    @Bean
    public JwtDecoder mcpJwtDecoder(JWKSource<SecurityContext> jwkSource, OAuthServerProperties oauth) {
        NimbusJwtDecoder decoder = (NimbusJwtDecoder) OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
        String resource = oauth.resourceUrlOrDefault();
        OAuth2TokenValidator<Jwt> audience = new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                aud -> aud != null && aud.contains(resource));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(oauth.issuerOrDefault()), audience));
        return decoder;
    }

    @Bean
    @Order(2)
    public SecurityFilterChain mcpFilterChain(HttpSecurity http, JwtDecoder mcpJwtDecoder,
                                              McpAuthenticationConverter converter,
                                              McpAuthenticationEntryPoint entryPoint,
                                              McpProperties props, OAuthServerProperties oauth) throws Exception {
        PathPatternRequestMatcher.Builder paths = PathPatternRequestMatcher.withDefaults();
        RequestMatcher metadata = new OrRequestMatcher(
                paths.matcher(METADATA_PATH),
                paths.matcher(METADATA_PATH + "/**"));
        RequestMatcher endpoint = new OrRequestMatcher(paths.matcher(MCP_PATH), paths.matcher(MCP_PATH + "/**"));

        http.securityMatcher(new OrRequestMatcher(metadata, endpoint))
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource(props)))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(metadata).permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, MCP_PATH).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resource -> resource
                        .jwt(jwt -> jwt.decoder(mcpJwtDecoder).jwtAuthenticationConverter(converter))
                        .protectedResourceMetadata(metadataEndpoint -> metadataEndpoint
                                .protectedResourceMetadataCustomizer(protectedResourceMetadata(oauth)))
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(entryPoint))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(entryPoint))
                .headers(headers -> headers.frameOptions(frame -> frame.deny()));
        return http.build();
    }

    /**
     * RFC 9728 document served by Spring Security at {@code /.well-known/oauth-protected-resource}
     * and {@code /.well-known/oauth-protected-resource/mcp}: the canonical resource, the issuer and
     * the scopes a client may request.
     */
    static Consumer<OAuth2ProtectedResourceMetadata.Builder> protectedResourceMetadata(OAuthServerProperties oauth) {
        return builder -> builder
                .resource(oauth.resourceUrlOrDefault())
                .authorizationServers(servers -> {
                    servers.clear();
                    servers.add(oauth.issuerOrDefault());
                })
                .scopes(scopes -> {
                    scopes.clear();
                    scopes.addAll(OAuthScope.resourceScopeValues());
                })
                .bearerMethods(methods -> {
                    methods.clear();
                    methods.add("header");
                })
                .resourceName("Stocka MCP");
    }

    static CorsConfigurationSource corsConfigurationSource(McpProperties props) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        CorsConfiguration open = new CorsConfiguration();
        open.setAllowedOriginPatterns(List.of("*"));
        open.setAllowedMethods(List.of("GET", "OPTIONS"));
        open.setAllowedHeaders(List.of("*"));
        open.setMaxAge(3600L);
        source.registerCorsConfiguration(METADATA_PATH + "/**", open);
        source.registerCorsConfiguration(METADATA_PATH, open);
        if (!props.getCorsAllowedOrigins().isEmpty()) {
            CorsConfiguration mcp = new CorsConfiguration();
            mcp.setAllowedOrigins(props.getCorsAllowedOrigins());
            mcp.setAllowedMethods(List.of("GET", "POST", "DELETE", "OPTIONS"));
            mcp.setAllowedHeaders(List.of("*"));
            mcp.setExposedHeaders(List.of("Mcp-Session-Id", "WWW-Authenticate"));
            mcp.setMaxAge(3600L);
            source.registerCorsConfiguration(MCP_PATH, mcp);
            source.registerCorsConfiguration(MCP_PATH + "/**", mcp);
        }
        return source;
    }
}
