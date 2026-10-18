package com.stocka.backend.modules.oauth.dcr;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.stocka.backend.modules.oauth.OAuthScope;
import com.stocka.backend.modules.oauth.OAuthServerConfiguration;
import com.stocka.backend.modules.oauth.OAuthServerProperties;
import com.stocka.backend.modules.oauth.client.RedirectUris;
import com.stocka.backend.modules.oauth.client.RegisteredClients;

/**
 * Anonymous dynamic client registration (RFC 7591) for MCP clients that do not publish a Client ID
 * Metadata Document.
 *
 * <p>Registers public clients only: no secret is ever issued, PKCE is mandatory and the grants are
 * limited to authorization code + refresh token (other requested grants are ignored). Redirect URIs must be https or loopback. The
 * endpoint is rate-limited per IP and clients that never obtain an authorization are purged by
 * {@link OAuthCleanupJob}. Errors follow RFC 7591 §3.2.2 ({@code invalid_redirect_uri},
 * {@code invalid_client_metadata}).
 */
@RestController
public class DynamicClientRegistrationController {

    private static final Logger log = LoggerFactory.getLogger(DynamicClientRegistrationController.class);
    static final int MAX_NAME_LENGTH = 120;
    static final int MAX_REDIRECT_URIS = 10;
    static final int MAX_URI_LENGTH = 512;

    private final RegisteredClientRepository clients;
    private final JdbcTemplate jdbc;
    private final OAuthServerProperties props;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public DynamicClientRegistrationController(RegisteredClientRepository clients, JdbcTemplate jdbc,
                                               OAuthServerProperties props) {
        this(clients, jdbc, props, Clock.systemUTC());
    }

    DynamicClientRegistrationController(RegisteredClientRepository clients, JdbcTemplate jdbc,
                                        OAuthServerProperties props, Clock clock) {
        this.clients = clients;
        this.jdbc = jdbc;
        this.props = props;
        this.clock = clock;
    }

    @PostMapping(path = OAuthServerConfiguration.REGISTRATION_PATH, consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> register(@RequestBody DynamicClientRegistrationRequest request) {
        if (!props.getDcr().isEnabled()) {
            return error(HttpStatus.NOT_FOUND, "invalid_client_metadata", "Dynamic client registration is disabled");
        }
        try {
            List<String> redirectUris = validatedRedirectUris(request.redirectUris());
            String name = validatedName(request.clientName(), redirectUris.get(0));
            validateGrants(request.grantTypes());
            validateResponseTypes(request.responseTypes());
            validateAuthMethod(request.tokenEndpointAuthMethod());

            Instant now = clock.instant();
            String clientId = randomClientId();
            RegisteredClient client = RegisteredClients.publicClient(UUID.randomUUID().toString(), clientId, name,
                    redirectUris, now, props);
            clients.save(client);
            jdbc.update("UPDATE oauth2_registered_client SET registered_via = 'DCR', client_uri = ?, logo_uri = ? WHERE id = ?",
                    httpsOrNull(request.clientUri()), httpsOrNull(request.logoUri()), client.getId());
            log.info("oauth_client_registered client_id={} name={} redirect_uris={}", clientId, name, redirectUris);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("client_id", clientId);
            body.put("client_id_issued_at", now.getEpochSecond());
            body.put("client_name", name);
            body.put("redirect_uris", redirectUris);
            body.put("grant_types", List.of("authorization_code", "refresh_token"));
            body.put("response_types", List.of("code"));
            body.put("token_endpoint_auth_method", "none");
            body.put("scope", String.join(" ", OAuthScope.allValues()));
            if (httpsOrNull(request.clientUri()) != null) body.put("client_uri", httpsOrNull(request.clientUri()));
            if (httpsOrNull(request.logoUri()) != null) body.put("logo_uri", httpsOrNull(request.logoUri()));
            return ResponseEntity.status(HttpStatus.CREATED).body(body);
        } catch (RegistrationException e) {
            return error(HttpStatus.BAD_REQUEST, e.code, e.getMessage());
        }
    }

    static List<String> validatedRedirectUris(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            throw new RegistrationException("invalid_redirect_uri", "redirect_uris is required");
        }
        if (raw.size() > MAX_REDIRECT_URIS) {
            throw new RegistrationException("invalid_redirect_uri", "too many redirect_uris");
        }
        Set<String> out = new LinkedHashSet<>();
        for (String uri : raw) {
            if (uri == null || uri.length() > MAX_URI_LENGTH || !RedirectUris.isRegistrable(uri)) {
                throw new RegistrationException("invalid_redirect_uri",
                        "redirect_uris must be https URLs or http loopback URLs without fragment");
            }
            out.add(uri.trim());
        }
        return new ArrayList<>(out);
    }

    static String validatedName(String raw, String firstRedirect) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) {
            name = RedirectUris.parse(firstRedirect).map(u -> u.getHost()).orElse("MCP client");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            name = name.substring(0, MAX_NAME_LENGTH);
        }
        return name;
    }

    /**
     * Grants the server does not support are ignored (RFC 7591 lets the server register a subset of
     * what was asked; the response advertises the effective grants), but the client must at least
     * ask for the authorization code grant.
     */
    static void validateGrants(List<String> grants) {
        if (grants == null || grants.isEmpty()) return;
        if (!grants.contains("authorization_code")) {
            throw new RegistrationException("invalid_client_metadata", "grant_types must include authorization_code");
        }
    }

    static void validateResponseTypes(List<String> types) {
        if (types == null) return;
        for (String type : types) {
            if (!"code".equals(type)) {
                throw new RegistrationException("invalid_client_metadata", "unsupported response_type: " + type);
            }
        }
    }

    static void validateAuthMethod(String method) {
        if (method != null && !method.isBlank() && !"none".equals(method)) {
            throw new RegistrationException("invalid_client_metadata",
                    "only public clients are supported (token_endpoint_auth_method=none)");
        }
    }

    static String httpsOrNull(String value) {
        if (value == null) return null;
        String v = value.trim();
        if (v.length() > MAX_URI_LENGTH || !v.toLowerCase(Locale.ROOT).startsWith("https://")) return null;
        return v;
    }

    private String randomClientId() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String code, String description) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("error_description", description);
        return ResponseEntity.status(status).body(body);
    }

    static final class RegistrationException extends RuntimeException {
        final String code;

        RegistrationException(String code, String message) {
            super(message);
            this.code = code;
        }
    }
}
