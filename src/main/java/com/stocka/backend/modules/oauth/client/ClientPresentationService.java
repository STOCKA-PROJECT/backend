package com.stocka.backend.modules.oauth.client;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.stereotype.Service;

import com.stocka.backend.modules.oauth.OAuthServerProperties;

/**
 * How a client is shown to people (consent page, connected apps): name, links, whether it is a
 * verified Client ID Metadata Document host and how it was registered.
 */
@Service
public class ClientPresentationService {

    /** Display data of a client. */
    public record ClientPresentation(String registeredClientId, String clientId, String name, String clientUri,
                                     String logoUri, boolean trusted, String registeredVia) {
    }

    private final RegisteredClientRepository clients;
    private final ClientMetadataDocumentClient documents;
    private final JdbcTemplate jdbc;
    private final OAuthServerProperties props;

    public ClientPresentationService(RegisteredClientRepository clients, ClientMetadataDocumentClient documents,
                                     JdbcTemplate jdbc, OAuthServerProperties props) {
        this.clients = clients;
        this.documents = documents;
        this.jdbc = jdbc;
        this.props = props;
    }

    /** @return the presentation of a resolved client */
    public ClientPresentation of(RegisteredClient client) {
        if (client.getId().startsWith(CimdRegisteredClientRepository.ID_PREFIX)) {
            return fromDocument(client.getId(), client.getClientId());
        }
        return fromRow(client.getId(), client.getClientId(), client.getClientName());
    }

    /**
     * @param registeredClientId storage id as kept in authorizations
     * @return the presentation, degrading to the bare id when the client is gone or unreachable
     */
    public ClientPresentation ofRegisteredClientId(String registeredClientId) {
        if (registeredClientId.startsWith(CimdRegisteredClientRepository.ID_PREFIX)) {
            List<String> urls = jdbc.query("SELECT client_id FROM oauth2_cimd_clients WHERE id = ?",
                    (rs, i) -> rs.getString(1), registeredClientId);
            String url = urls.isEmpty() ? registeredClientId : urls.get(0);
            return fromDocument(registeredClientId, url);
        }
        RegisteredClient client = clients.findById(registeredClientId);
        if (client == null) {
            return new ClientPresentation(registeredClientId, registeredClientId, registeredClientId, null, null, false,
                    "UNKNOWN");
        }
        return fromRow(client.getId(), client.getClientId(), client.getClientName());
    }

    private ClientPresentation fromDocument(String id, String url) {
        boolean trusted = props.getCimd().isTrustedHost(CimdRegisteredClientRepository.hostOf(url));
        try {
            ClientMetadataDocument doc = documents.fetch(url);
            return new ClientPresentation(id, url, doc.clientName(), doc.clientUri(), doc.logoUri(), trusted, "CIMD");
        } catch (ClientMetadataDocumentException e) {
            String host = Optional.ofNullable(CimdRegisteredClientRepository.hostOf(url)).orElse(url);
            return new ClientPresentation(id, url, host, null, null, false, "CIMD");
        }
    }

    private ClientPresentation fromRow(String id, String clientId, String name) {
        List<ClientPresentation> rows = jdbc.query(
                "SELECT client_uri, logo_uri, registered_via FROM oauth2_registered_client WHERE id = ?",
                (rs, i) -> new ClientPresentation(id, clientId, name, rs.getString("client_uri"),
                        rs.getString("logo_uri"), "MANUAL".equals(rs.getString("registered_via")),
                        rs.getString("registered_via")),
                id);
        return rows.isEmpty() ? new ClientPresentation(id, clientId, name, null, null, false, "MANUAL") : rows.get(0);
    }
}
