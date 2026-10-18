package com.stocka.backend.modules.oauth;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationServerMetadata;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationValidator;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.DelegatingOAuth2TokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.JwtGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2AccessTokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.stocka.backend.modules.oauth.client.CimdRegisteredClientRepository;
import com.stocka.backend.modules.oauth.client.ClientMetadataDocumentClient;
import com.stocka.backend.modules.security.audit.SecurityAuditService;
import com.stocka.backend.modules.security.error.JsonAuthenticationEntryPoint;
import com.stocka.backend.modules.security.service.JwtService;
import com.stocka.backend.modules.users.repository.UserRepository;

/**
 * The OAuth 2.1 authorization server used by MCP connectors, as a security filter chain of its own
 * ({@code @Order(1)}) in front of the REST chain.
 *
 * <p>Endpoints: Spring Authorization Server's {@code /oauth2/authorize}, {@code /oauth2/token},
 * {@code /oauth2/jwks}, {@code /oauth2/revoke} and {@code /.well-known/oauth-authorization-server},
 * plus Stocka's own {@code /oauth2/register} (dynamic registration), {@code /oauth2/consent} and the
 * browser login under {@code /auth/connect/**}. Only this chain creates HTTP sessions, and only for
 * the duration of an authorization.
 */
@Configuration
@EnableConfigurationProperties(OAuthServerProperties.class)
@ConditionalOnProperty(prefix = "stocka.oauth", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OAuthServerConfiguration {

    /** Browser login page of the authorization server. */
    public static final String LOGIN_PATH = "/auth/connect/login";
    /** Consent page the authorize endpoint redirects to. */
    public static final String CONSENT_PATH = "/oauth2/consent";
    /** Dynamic client registration endpoint (RFC 7591). */
    public static final String REGISTRATION_PATH = "/oauth2/register";

    private static final String CSP = "default-src 'self'; style-src 'self' 'unsafe-inline'; "
            + "img-src 'self' https: data:; form-action 'self'; frame-ancestors 'none'; base-uri 'self'";

    @Bean
    public AuthorizationServerSettings authorizationServerSettings(OAuthServerProperties props) {
        return AuthorizationServerSettings.builder().issuer(props.issuerOrDefault()).build();
    }

    @Bean
    public JWKSource<SecurityContext> oauthJwkSource(OAuthServerProperties props, Environment environment) {
        JWKSet keys = OAuthKeyLoader.load(props.getJwk(), environment.acceptsProfiles(Profiles.of("prod")));
        return new ImmutableJWKSet<>(keys);
    }

    @Bean
    public RegisteredClientRepository registeredClientRepository(JdbcTemplate jdbc,
                                                                 ClientMetadataDocumentClient documents,
                                                                 OAuthServerProperties props) {
        return new CimdRegisteredClientRepository(new JdbcRegisteredClientRepository(jdbc), documents, jdbc, props,
                Clock.systemUTC());
    }

    @Bean
    public OAuth2AuthorizationService oauth2AuthorizationService(JdbcTemplate jdbc, RegisteredClientRepository clients) {
        return new JdbcOAuth2AuthorizationService(jdbc, clients);
    }

    @Bean
    public OAuth2AuthorizationConsentService oauth2AuthorizationConsentService(JdbcTemplate jdbc,
                                                                               RegisteredClientRepository clients) {
        return new JdbcOAuth2AuthorizationConsentService(jdbc, clients);
    }

    @Bean
    public OAuthTokenCustomizer oauthTokenCustomizer(OAuthServerProperties props, UserRepository users,
                                                     JwtService jwtService, SecurityAuditService audit) {
        return new OAuthTokenCustomizer(props, users, jwtService, audit);
    }

    @Bean
    public OAuth2TokenGenerator<?> oauthTokenGenerator(JWKSource<SecurityContext> jwkSource,
                                                       OAuthTokenCustomizer customizer,
                                                       OAuthServerProperties props) {
        JwtGenerator jwtGenerator = new JwtGenerator(new NimbusJwtEncoder(jwkSource));
        jwtGenerator.setJwtCustomizer(customizer);
        return new DelegatingOAuth2TokenGenerator(jwtGenerator, new OAuth2AccessTokenGenerator(),
                new OAuthRefreshTokenGenerator(props, Clock.systemUTC()));
    }

    @Bean
    public SecurityContextRepository oauthSecurityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public RequestCache oauthRequestCache() {
        return new HttpSessionRequestCache();
    }

    @Bean
    @Order(1)
    public SecurityFilterChain oauthAuthorizationServerFilterChain(
            HttpSecurity http,
            RegisteredClientRepository clients,
            OAuth2AuthorizationService authorizations,
            OAuth2AuthorizationConsentService consents,
            AuthorizationServerSettings settings,
            OAuth2TokenGenerator<?> tokenGenerator,
            OAuthServerProperties props,
            SecurityContextRepository securityContextRepository,
            RequestCache requestCache,
            JsonAuthenticationEntryPoint jsonEntryPoint) throws Exception {

        OAuth2AuthorizationServerConfigurer authorizationServer = new OAuth2AuthorizationServerConfigurer();
        RequestMatcher serverEndpoints = authorizationServer.getEndpointsMatcher();
        PathPatternRequestMatcher.Builder paths = PathPatternRequestMatcher.withDefaults();
        RequestMatcher registration = paths.matcher(REGISTRATION_PATH);
        RequestMatcher ownPages = new OrRequestMatcher(
                paths.matcher("/auth/connect/**"), paths.matcher(CONSENT_PATH), registration);

        LoginUrlAuthenticationEntryPoint loginEntryPoint = new LoginUrlAuthenticationEntryPoint(LOGIN_PATH);
        MediaTypeRequestMatcher html = new MediaTypeRequestMatcher(MediaType.TEXT_HTML);
        html.setIgnoredMediaTypes(Set.of(MediaType.ALL));

        http.securityMatcher(new OrRequestMatcher(serverEndpoints, ownPages));
        http.with(authorizationServer, server -> server
                .registeredClientRepository(clients)
                .authorizationService(authorizations)
                .authorizationConsentService(consents)
                .authorizationServerSettings(settings)
                .tokenGenerator(tokenGenerator)
                .authorizationEndpoint(endpoint -> endpoint
                        .consentPage(CONSENT_PATH)
                        .authorizationResponseHandler(new IssuerAuthorizationResponseHandler(props))
                        .authenticationProviders(providers -> providers.forEach(provider -> {
                            if (provider instanceof OAuth2AuthorizationCodeRequestAuthenticationProvider codeRequest) {
                                codeRequest.setAuthenticationValidator(new OAuthRedirectUriValidator(props)
                                        .andThen(OAuth2AuthorizationCodeRequestAuthenticationValidator.DEFAULT_SCOPE_VALIDATOR));
                            }
                        })))
                .clientAuthentication(client -> client
                        .authenticationConverters(converters ->
                                converters.add(new PublicClientRefreshAuthentication.Converter()))
                        .authenticationProviders(providers ->
                                providers.add(new PublicClientRefreshAuthentication.Provider(clients))))
                .authorizationServerMetadataEndpoint(metadata ->
                        metadata.authorizationServerMetadataCustomizer(metadataCustomizer(props))));
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(registration, paths.matcher("/auth/connect/**")).permitAll()
                        .anyRequest().authenticated())
                .csrf(csrf -> csrf.ignoringRequestMatchers(serverEndpoints, registration))
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .securityContext(context -> context.securityContextRepository(securityContextRepository))
                .requestCache(cache -> cache.requestCache(requestCache))
                .exceptionHandling(exceptions -> exceptions
                        .defaultAuthenticationEntryPointFor(loginEntryPoint, html)
                        .defaultAuthenticationEntryPointFor(jsonEntryPoint, AnyRequestMatcher.INSTANCE))
                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny())
                        .contentSecurityPolicy(csp -> csp.policyDirectives(CSP)));
        return http.build();
    }

    /**
     * Advertises what MCP clients look for: CIMD support, the registration endpoint (when enabled),
     * the scopes, {@code iss} on authorization responses and public-client token authentication.
     */
    static Consumer<OAuth2AuthorizationServerMetadata.Builder> metadataCustomizer(OAuthServerProperties props) {
        return builder -> {
            builder.claim("client_id_metadata_document_supported", true);
            builder.claim("authorization_response_iss_parameter_supported", true);
            builder.claim("scopes_supported", OAuthScope.allValues());
            builder.claim("grant_types_supported", List.of(
                    AuthorizationGrantType.AUTHORIZATION_CODE.getValue(),
                    AuthorizationGrantType.REFRESH_TOKEN.getValue()));
            builder.claim("token_endpoint_auth_methods_supported", List.of("none"));
            builder.claim("revocation_endpoint_auth_methods_supported", List.of("none"));
            builder.claim("code_challenge_methods_supported", List.of("S256"));
            if (props.getDcr().isEnabled()) {
                builder.clientRegistrationEndpoint(props.issuerOrDefault() + REGISTRATION_PATH);
            }
        };
    }

    /** Browser-based MCP clients call the token, registration and metadata endpoints cross-origin. */
    static CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration open = new CorsConfiguration();
        open.setAllowedOriginPatterns(List.of("*"));
        open.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        open.setAllowedHeaders(List.of("*"));
        open.setAllowCredentials(false);
        open.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/oauth2/token", open);
        source.registerCorsConfiguration("/oauth2/revoke", open);
        source.registerCorsConfiguration("/oauth2/jwks", open);
        source.registerCorsConfiguration(REGISTRATION_PATH, open);
        source.registerCorsConfiguration("/.well-known/**", open);
        return source;
    }
}
