package com.stocka.backend.modules.oauth.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.stocka.backend.modules.oauth.OAuthServerProperties;
import com.stocka.backend.modules.oauth.client.ClientMetadataDocumentException.Reason;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Fetches and validates Client ID Metadata Documents (draft-ietf-oauth-client-id-metadata-document).
 *
 * <p>A URL-shaped {@code client_id} is the document's own address. The document is only accepted
 * when it is served over https, is small, parses as JSON, repeats the exact URL in {@code client_id},
 * declares at least one registrable redirect URI and does not ask for a client secret or for grants
 * other than authorization code + refresh token. Documents are cached for
 * {@code stocka.oauth.cimd.cache-ttl} so the authorize and token requests of one flow do not hit the
 * client's host twice.
 */
@Component
public class ClientMetadataDocumentClient {

    private static final Logger log = LoggerFactory.getLogger(ClientMetadataDocumentClient.class);
    private static final int MAX_NAME_LENGTH = 120;

    private final OAuthServerProperties props;
    private final RestClient http;
    private final Clock clock;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    @Autowired
    public ClientMetadataDocumentClient(OAuthServerProperties props) {
        this(props, defaultBuilder(props), Clock.systemUTC());
    }

    ClientMetadataDocumentClient(OAuthServerProperties props, RestClient.Builder builder, Clock clock) {
        this.props = props;
        this.http = builder.build();
        this.clock = clock;
    }

    private static RestClient.Builder defaultBuilder(OAuthServerProperties props) {
        HttpClient jdk = HttpClient.newBuilder()
                .connectTimeout(props.getCimd().getTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdk);
        factory.setReadTimeout(props.getCimd().getTimeout());
        return RestClient.builder().requestFactory(factory);
    }

    /** @return whether {@code clientId} has the shape of a metadata document URL */
    public static boolean isMetadataUrl(String clientId) {
        if (clientId == null) return false;
        String v = clientId.trim().toLowerCase(Locale.ROOT);
        if (!v.startsWith("https://")) return false;
        int slash = v.indexOf('/', "https://".length());
        return slash > 0 && slash < v.length() - 1;
    }

    /**
     * Fetches (or serves from cache) the document at {@code url}.
     *
     * @param url the client id
     * @return the validated document
     * @throws ClientMetadataDocumentException when unreachable or invalid
     */
    public ClientMetadataDocument fetch(String url) {
        if (!isMetadataUrl(url)) {
            throw new ClientMetadataDocumentException(Reason.INVALID, "client_id is not an https URL with a path");
        }
        Cached cached = cache.get(url);
        Instant now = clock.instant();
        if (cached != null && cached.fetchedAt.plus(props.getCimd().getCacheTtl()).isAfter(now)) {
            return cached.document;
        }
        ClientMetadataDocument document = download(url);
        cache.put(url, new Cached(document, now));
        return document;
    }

    /** Drops every cached document (tests and manual refresh). */
    public void clearCache() {
        cache.clear();
    }

    private ClientMetadataDocument download(String url) {
        String body;
        try {
            body = http.get().uri(URI.create(url))
                    .header("Accept", "application/json")
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException | IllegalArgumentException e) {
            log.info("cimd_unreachable url={} reason={}", url, e.getMessage());
            throw new ClientMetadataDocumentException(Reason.UNREACHABLE,
                    "Client metadata document could not be fetched", e);
        }
        if (body == null || body.isBlank()) {
            throw new ClientMetadataDocumentException(Reason.INVALID, "Client metadata document is empty");
        }
        if (body.length() > props.getCimd().getMaxBytes()) {
            throw new ClientMetadataDocumentException(Reason.INVALID, "Client metadata document is too large");
        }
        return parse(url, body);
    }

    /**
     * Validates a document body.
     *
     * @param url  expected {@code client_id}
     * @param body JSON text
     * @return the document
     * @throws ClientMetadataDocumentException on any violation
     */
    ClientMetadataDocument parse(String url, String body) {
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (JacksonException e) {
            throw new ClientMetadataDocumentException(Reason.INVALID, "Client metadata document is not JSON", e);
        }
        if (root == null || !root.isObject()) {
            throw new ClientMetadataDocumentException(Reason.INVALID, "Client metadata document must be an object");
        }
        String clientId = root.path("client_id").asText(null);
        if (clientId == null || !clientId.equals(url)) {
            throw new ClientMetadataDocumentException(Reason.INVALID, "client_id in the document must equal its URL");
        }
        String name = root.path("client_name").asText(null);
        if (name == null || name.isBlank()) {
            throw new ClientMetadataDocumentException(Reason.INVALID, "client_name is required");
        }
        name = name.trim();
        if (name.length() > MAX_NAME_LENGTH) {
            name = name.substring(0, MAX_NAME_LENGTH);
        }
        List<String> redirectUris = new ArrayList<>();
        JsonNode uris = root.path("redirect_uris");
        if (!uris.isArray()) {
            throw new ClientMetadataDocumentException(Reason.INVALID, "redirect_uris is required");
        }
        for (JsonNode uri : uris) {
            String value = uri.asText(null);
            if (value == null || !RedirectUris.isRegistrable(value)) {
                throw new ClientMetadataDocumentException(Reason.INVALID, "redirect_uris contains an invalid URI");
            }
            redirectUris.add(value.trim());
        }
        if (redirectUris.isEmpty()) {
            throw new ClientMetadataDocumentException(Reason.INVALID, "redirect_uris must not be empty");
        }
        JsonNode authMethod = root.path("token_endpoint_auth_method");
        if (!authMethod.isMissingNode() && !authMethod.isNull() && !"none".equals(authMethod.asText())) {
            throw new ClientMetadataDocumentException(Reason.INVALID, "only public clients (token_endpoint_auth_method=none) are supported");
        }
        // The document lists everything the client can do; we only honour authorization_code
        // (+ refresh_token). Other grants (Claude.ai declares jwt-bearer, for instance) are ignored,
        // as RFC 7591 lets a server register a subset of the requested capabilities.
        JsonNode grants = root.path("grant_types");
        if (grants.isArray() && !grants.isEmpty() && !containsText(grants, "authorization_code")) {
            throw new ClientMetadataDocumentException(Reason.INVALID, "grant_types must include authorization_code");
        }
        String clientUri = optionalHttpsUrl(root, "client_uri");
        String logoUri = optionalHttpsUrl(root, "logo_uri");
        return new ClientMetadataDocument(clientId, name, clientUri, logoUri, List.copyOf(redirectUris));
    }

    private static boolean containsText(JsonNode array, String value) {
        for (JsonNode node : array) {
            if (value.equals(node.asText(""))) return true;
        }
        return false;
    }

    private static String optionalHttpsUrl(JsonNode root, String field) {
        String value = root.path(field).asText(null);
        if (value == null || value.isBlank()) return null;
        return value.trim().toLowerCase(Locale.ROOT).startsWith("https://") ? value.trim() : null;
    }

    private record Cached(ClientMetadataDocument document, Instant fetchedAt) {
    }
}
