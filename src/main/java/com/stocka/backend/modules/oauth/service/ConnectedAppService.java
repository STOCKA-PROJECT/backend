package com.stocka.backend.modules.oauth.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.stocka.backend.modules.common.error.ApiException;
import com.stocka.backend.modules.common.error.ErrorCodes;
import com.stocka.backend.modules.oauth.client.CimdRegisteredClientRepository;
import com.stocka.backend.modules.oauth.client.ClientMetadataDocumentClient;
import com.stocka.backend.modules.oauth.client.ClientPresentationService;
import com.stocka.backend.modules.oauth.client.ClientPresentationService.ClientPresentation;
import com.stocka.backend.modules.oauth.dto.ConnectedAppDto;
import com.stocka.backend.modules.security.audit.SecurityAuditService;
import com.stocka.backend.modules.security.audit.SecurityEventType;
import com.stocka.backend.modules.users.entity.User;

/**
 * The OAuth clients a user has authorized ("connected apps"), aggregated from the authorization
 * server's {@code oauth2_authorization} rows, and their revocation.
 *
 * <p>Revoking deletes every authorization of the (user, client) pair, so refresh tokens die at once,
 * and forgets the stored consent so the next connection shows the consent page again. Access tokens
 * are validated against their authorization row on every MCP call, so they stop working too.
 */
@Service
public class ConnectedAppService {

    private static final Logger log = LoggerFactory.getLogger(ConnectedAppService.class);

    private final JdbcTemplate jdbc;
    private final OAuth2AuthorizationConsentService consents;
    private final ClientPresentationService presentations;
    private final SecurityAuditService audit;

    public ConnectedAppService(JdbcTemplate jdbc, OAuth2AuthorizationConsentService consents,
                               ClientPresentationService presentations, SecurityAuditService audit) {
        this.jdbc = jdbc;
        this.consents = consents;
        this.presentations = presentations;
        this.audit = audit;
    }

    /** @return the apps of {@code user}, most recently used first */
    public List<ConnectedAppDto> list(User user) {
        Map<String, Aggregate> byClient = new LinkedHashMap<>();
        jdbc.query("""
                SELECT registered_client_id, authorized_scopes, authorization_code_issued_at,
                       access_token_issued_at, access_token_expires_at, refresh_token_expires_at
                  FROM oauth2_authorization
                 WHERE principal_name = ?
                 ORDER BY access_token_issued_at
                """, rs -> {
            Aggregate agg = byClient.computeIfAbsent(rs.getString("registered_client_id"), Aggregate::new);
            agg.add(rs.getString("authorized_scopes"),
                    instant(rs.getTimestamp("authorization_code_issued_at")),
                    instant(rs.getTimestamp("access_token_issued_at")),
                    instant(rs.getTimestamp("access_token_expires_at")),
                    instant(rs.getTimestamp("refresh_token_expires_at")));
        }, String.valueOf(user.getId()));

        List<ConnectedAppDto> out = new ArrayList<>();
        for (Aggregate agg : byClient.values()) {
            ClientPresentation client = presentations.ofRegisteredClientId(agg.registeredClientId);
            out.add(new ConnectedAppDto(client.clientId(), client.name(), client.clientUri(), client.logoUri(),
                    client.trusted(), client.registeredVia(), new ArrayList<>(agg.scopes), agg.grantedAt,
                    agg.lastUsedAt, agg.expiresAt));
        }
        out.sort((a, b) -> compareDesc(a.lastUsedAt(), b.lastUsedAt()));
        return out;
    }

    /**
     * Revokes every grant of {@code user} to the client identified by its wire {@code clientId}.
     *
     * @throws ApiException 404 when the user has no grant for that client
     */
    @Transactional
    public void revoke(User user, String clientId) {
        String registeredClientId = registeredClientIdOf(clientId);
        String principal = String.valueOf(user.getId());
        int removed = registeredClientId == null ? 0 : jdbc.update(
                "DELETE FROM oauth2_authorization WHERE principal_name = ? AND registered_client_id = ?",
                principal, registeredClientId);
        OAuth2AuthorizationConsent consent = registeredClientId == null ? null
                : consents.findById(registeredClientId, principal);
        if (consent != null) {
            consents.remove(consent);
        }
        if (removed == 0 && consent == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.OAUTH_APP_NOT_FOUND);
        }
        audit.recordSuccess(SecurityEventType.OAUTH_APP_REVOKED, user,
                "{\"client\":\"" + clientId.replace("\"", "") + "\"}");
        log.info("oauth_app_revoked user={} client={} authorizations={}", user.getId(), clientId, removed);
    }

    /** Revokes every grant of {@code user} (password change, account deletion). */
    @Transactional
    public int revokeAll(User user) {
        String principal = String.valueOf(user.getId());
        int removed = jdbc.update("DELETE FROM oauth2_authorization WHERE principal_name = ?", principal);
        jdbc.update("DELETE FROM oauth2_authorization_consent WHERE principal_name = ?", principal);
        if (removed > 0) {
            log.info("oauth_apps_revoked_all user={} authorizations={}", user.getId(), removed);
        }
        return removed;
    }

    private String registeredClientIdOf(String clientId) {
        if (clientId == null || clientId.isBlank()) return null;
        if (ClientMetadataDocumentClient.isMetadataUrl(clientId)) {
            return CimdRegisteredClientRepository.idFor(clientId.trim());
        }
        List<String> ids = jdbc.query("SELECT id FROM oauth2_registered_client WHERE client_id = ?",
                (rs, i) -> rs.getString(1), clientId.trim());
        return ids.isEmpty() ? null : ids.get(0);
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private static int compareDesc(Instant a, Instant b) {
        if (a == null && b == null) return 0;
        if (a == null) return 1;
        if (b == null) return -1;
        return b.compareTo(a);
    }

    /** Running aggregate of one client's authorizations. */
    private static final class Aggregate {
        final String registeredClientId;
        final Set<String> scopes = new TreeSet<>();
        Instant grantedAt;
        Instant lastUsedAt;
        Instant expiresAt;

        Aggregate(String registeredClientId) {
            this.registeredClientId = registeredClientId;
        }

        void add(String authorizedScopes, Instant codeIssued, Instant accessIssued, Instant accessExpires,
                 Instant refreshExpires) {
            if (authorizedScopes != null) {
                for (String s : authorizedScopes.split(",")) {
                    if (!s.isBlank()) scopes.add(s.trim());
                }
            }
            grantedAt = min(grantedAt, codeIssued);
            lastUsedAt = max(lastUsedAt, accessIssued);
            expiresAt = max(expiresAt, max(accessExpires, refreshExpires));
        }

        private static Instant min(Instant a, Instant b) {
            if (a == null) return b;
            if (b == null) return a;
            return a.isBefore(b) ? a : b;
        }

        private static Instant max(Instant a, Instant b) {
            if (a == null) return b;
            if (b == null) return a;
            return a.isAfter(b) ? a : b;
        }
    }
}
