package com.stocka.backend.modules.oauth.client;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

import com.stocka.backend.modules.oauth.OAuthServerProperties;

/**
 * {@link RegisteredClientRepository} that understands Client ID Metadata Documents on top of the
 * JDBC-backed registered clients.
 *
 * <p>A {@code client_id} that is an https URL is resolved by fetching the document (see
 * {@link ClientMetadataDocumentClient}); nothing about it is stored in {@code oauth2_registered_client}.
 * Because Spring Authorization Server references clients by a short {@code id} in authorizations and
 * consents, each document URL gets a stable synthetic id ({@code cimd:} + SHA-256 of the URL) kept in
 * {@code oauth2_cimd_clients}, so {@link #findById(String)} can map it back to the URL and re-fetch.
 */
public class CimdRegisteredClientRepository implements RegisteredClientRepository {

    private static final Logger log = LoggerFactory.getLogger(CimdRegisteredClientRepository.class);
    static final String ID_PREFIX = "cimd:";

    private final RegisteredClientRepository delegate;
    private final ClientMetadataDocumentClient documents;
    private final JdbcTemplate jdbc;
    private final OAuthServerProperties props;
    private final Clock clock;

    public CimdRegisteredClientRepository(RegisteredClientRepository delegate, ClientMetadataDocumentClient documents,
                                          JdbcTemplate jdbc, OAuthServerProperties props, Clock clock) {
        this.delegate = delegate;
        this.documents = documents;
        this.jdbc = jdbc;
        this.props = props;
        this.clock = clock;
    }

    @Override
    public void save(RegisteredClient registeredClient) {
        if (registeredClient.getId().startsWith(ID_PREFIX)) {
            throw new IllegalArgumentException("CIMD clients are not stored");
        }
        delegate.save(registeredClient);
    }

    @Override
    public RegisteredClient findById(String id) {
        if (id != null && id.startsWith(ID_PREFIX)) {
            return urlForId(id).map(this::resolve).orElse(null);
        }
        return delegate.findById(id);
    }

    @Override
    public RegisteredClient findByClientId(String clientId) {
        if (ClientMetadataDocumentClient.isMetadataUrl(clientId)) {
            RegisteredClient client = resolve(clientId.trim());
            if (client != null) {
                remember(client.getId(), clientId.trim());
            }
            return client;
        }
        return delegate.findByClientId(clientId);
    }

    /** @return whether the client's host is in the trusted list (consent page badge) */
    public boolean isTrusted(RegisteredClient client) {
        if (client == null || !client.getId().startsWith(ID_PREFIX)) return false;
        return props.getCimd().isTrustedHost(hostOf(client.getClientId()));
    }

    /** @return the stable storage id of a metadata URL */
    public static String idFor(String url) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(url.getBytes(StandardCharsets.UTF_8));
            return ID_PREFIX + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private RegisteredClient resolve(String url) {
        try {
            ClientMetadataDocument doc = documents.fetch(url);
            return RegisteredClients.publicClient(idFor(url), url, doc.clientName(), doc.redirectUris(),
                    Instant.EPOCH, props);
        } catch (ClientMetadataDocumentException e) {
            log.info("cimd_rejected url={} reason={} detail={}", url, e.getReason(), e.getMessage());
            return null;
        }
    }

    private Optional<String> urlForId(String id) {
        List<String> urls = jdbc.query("SELECT client_id FROM oauth2_cimd_clients WHERE id = ?",
                (rs, i) -> rs.getString(1), id);
        return urls.stream().findFirst();
    }

    private void remember(String id, String url) {
        Timestamp now = Timestamp.from(clock.instant());
        int updated = jdbc.update("UPDATE oauth2_cimd_clients SET last_seen_at = ? WHERE id = ?", now, id);
        if (updated == 0) {
            jdbc.update("INSERT INTO oauth2_cimd_clients (id, client_id, first_seen_at, last_seen_at) VALUES (?, ?, ?, ?)",
                    id, url, now, now);
        }
    }

    static String hostOf(String url) {
        return RedirectUris.parse(url).map(u -> u.getHost()).orElse(null);
    }
}
