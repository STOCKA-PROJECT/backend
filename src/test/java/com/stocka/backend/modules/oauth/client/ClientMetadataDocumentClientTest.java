package com.stocka.backend.modules.oauth.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.stocka.backend.modules.oauth.OAuthServerProperties;
import com.stocka.backend.modules.oauth.client.ClientMetadataDocumentException.Reason;

@DisplayName("ClientMetadataDocumentClient")
class ClientMetadataDocumentClientTest {

    static final String URL = "https://claude.ai/oauth/claude-code-client-metadata";
    static final String VALID = """
            {"client_id": "%s", "client_name": "Claude Code", "client_uri": "https://claude.ai",
             "redirect_uris": ["http://localhost/callback", "http://127.0.0.1/callback"],
             "grant_types": ["authorization_code", "refresh_token"], "token_endpoint_auth_method": "none"}
            """.formatted(URL);

    private final OAuthServerProperties props = new OAuthServerProperties();
    private MockRestServiceServer server;
    private ClientMetadataDocumentClient client;
    private Instant now = Instant.parse("2026-10-18T10:00:00Z");

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new ClientMetadataDocumentClient(props, builder, new MutableClock());
    }

    class MutableClock extends Clock {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Nested
    @DisplayName("isMetadataUrl")
    class Shape {

        @Test
        @DisplayName("needs https and a path")
        void shape() {
            assertThat(ClientMetadataDocumentClient.isMetadataUrl(URL)).isTrue();
            assertThat(ClientMetadataDocumentClient.isMetadataUrl("https://x.example/")).isFalse();
            assertThat(ClientMetadataDocumentClient.isMetadataUrl("https://x.example")).isFalse();
            assertThat(ClientMetadataDocumentClient.isMetadataUrl("http://x.example/c.json")).isFalse();
            assertThat(ClientMetadataDocumentClient.isMetadataUrl("abc123")).isFalse();
            assertThat(ClientMetadataDocumentClient.isMetadataUrl(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("fetch")
    class Fetch {

        @Test
        @DisplayName("downloads, validates and caches the document")
        void fetchesOnce() {
            server.expect(requestTo(URL)).andRespond(withSuccess(VALID, MediaType.APPLICATION_JSON));
            ClientMetadataDocument doc = client.fetch(URL);
            assertThat(doc.clientName()).isEqualTo("Claude Code");
            assertThat(doc.redirectUris()).containsExactly("http://localhost/callback", "http://127.0.0.1/callback");
            assertThat(doc.clientUri()).isEqualTo("https://claude.ai");
            assertThat(client.fetch(URL)).isSameAs(doc);
            server.verify();
        }

        @Test
        @DisplayName("re-downloads after the cache TTL")
        void cacheExpires() {
            server.expect(requestTo(URL)).andRespond(withSuccess(VALID, MediaType.APPLICATION_JSON));
            server.expect(requestTo(URL)).andRespond(withSuccess(VALID, MediaType.APPLICATION_JSON));
            client.fetch(URL);
            now = now.plus(props.getCimd().getCacheTtl()).plusSeconds(1);
            client.fetch(URL);
            server.verify();
        }

        @Test
        @DisplayName("an unreachable host is UNREACHABLE")
        void unreachable() {
            server.expect(requestTo(URL)).andRespond(withServerError());
            assertThatThrownBy(() -> client.fetch(URL))
                    .isInstanceOf(ClientMetadataDocumentException.class)
                    .extracting(e -> ((ClientMetadataDocumentException) e).getReason()).isEqualTo(Reason.UNREACHABLE);
        }

        @Test
        @DisplayName("a non-URL client id is INVALID without any request")
        void notUrl() {
            assertThatThrownBy(() -> client.fetch("abc")).isInstanceOf(ClientMetadataDocumentException.class);
            server.verify();
        }

        @Test
        @DisplayName("an oversized document is rejected")
        void tooLarge() {
            props.getCimd().setMaxBytes(50);
            server.expect(requestTo(URL)).andRespond(withSuccess(VALID, MediaType.APPLICATION_JSON));
            assertThatThrownBy(() -> client.fetch(URL)).hasMessageContaining("too large");
        }
    }

    @Nested
    @DisplayName("parse")
    class Parse {

        private ClientMetadataDocumentException parseError(String body) {
            try {
                client.parse(URL, body);
                throw new AssertionError("expected rejection");
            } catch (ClientMetadataDocumentException e) {
                return e;
            }
        }

        @Test
        @DisplayName("client_id must equal the URL")
        void clientIdMismatch() {
            assertThat(parseError(VALID.replace(URL, "https://other.example/c.json")).getMessage()).contains("client_id");
        }

        @Test
        @DisplayName("not JSON, not an object")
        void notJson() {
            assertThat(parseError("<html>").getReason()).isEqualTo(Reason.INVALID);
            assertThat(parseError("[1,2]").getMessage()).contains("object");
        }

        @Test
        @DisplayName("client_name and redirect_uris are required and redirect URIs must be registrable")
        void required() {
            assertThat(parseError("{\"client_id\":\"" + URL + "\",\"redirect_uris\":[\"https://a/b\"]}").getMessage())
                    .contains("client_name");
            assertThat(parseError("{\"client_id\":\"" + URL + "\",\"client_name\":\"x\"}").getMessage())
                    .contains("redirect_uris");
            assertThat(parseError("{\"client_id\":\"" + URL + "\",\"client_name\":\"x\",\"redirect_uris\":[]}").getMessage())
                    .contains("empty");
            assertThat(parseError("{\"client_id\":\"" + URL + "\",\"client_name\":\"x\",\"redirect_uris\":[\"http://evil.example/x\"]}")
                    .getMessage()).contains("invalid URI");
        }

        @Test
        @DisplayName("confidential clients and other grants are rejected")
        void publicOnly() {
            assertThat(parseError(VALID.replace("\"none\"", "\"client_secret_basic\"")).getMessage()).contains("public");
            assertThat(parseError(VALID.replace("\"refresh_token\"", "\"client_credentials\"")).getMessage())
                    .contains("grant_type");
        }

        @Test
        @DisplayName("long names are truncated and non-https links dropped")
        void sanitised() {
            String longName = "n".repeat(200);
            ClientMetadataDocument doc = client.parse(URL, VALID.replace("Claude Code", longName)
                    .replace("https://claude.ai\"", "http://claude.ai\""));
            assertThat(doc.clientName()).hasSize(120);
            assertThat(doc.clientUri()).isNull();
        }
    }
}
