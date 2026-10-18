package com.stocka.backend.modules.oauth.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

import com.stocka.backend.modules.oauth.OAuthServerProperties;
import com.stocka.backend.modules.oauth.client.ClientMetadataDocumentException.Reason;

@DisplayName("CimdRegisteredClientRepository")
class CimdRegisteredClientRepositoryTest {

    static final String URL = "https://claude.ai/oauth/claude-code-client-metadata";

    private final RegisteredClientRepository delegate = mock(RegisteredClientRepository.class);
    private final ClientMetadataDocumentClient documents = mock(ClientMetadataDocumentClient.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final OAuthServerProperties props = new OAuthServerProperties();
    private final CimdRegisteredClientRepository repository = new CimdRegisteredClientRepository(delegate, documents, jdbc,
            props, Clock.fixed(Instant.parse("2026-10-18T10:00:00Z"), ZoneOffset.UTC));
    private final ClientMetadataDocument doc = new ClientMetadataDocument(URL, "Claude Code", null, null,
            List.of("http://localhost/callback"));

    @Nested
    @DisplayName("URL client ids")
    class Urls {

        @Test
        @DisplayName("resolve through the document, as a public PKCE client, and are remembered")
        void resolves() {
            when(documents.fetch(URL)).thenReturn(doc);
            when(jdbc.update(anyString(), any(), any())).thenReturn(0);
            RegisteredClient client = repository.findByClientId(URL);
            assertThat(client).isNotNull();
            assertThat(client.getId()).isEqualTo(CimdRegisteredClientRepository.idFor(URL)).startsWith("cimd:").hasSizeLessThanOrEqualTo(100);
            assertThat(client.getClientId()).isEqualTo(URL);
            assertThat(client.getClientName()).isEqualTo("Claude Code");
            assertThat(client.getRedirectUris()).containsExactly("http://localhost/callback");
            assertThat(client.getClientAuthenticationMethods()).containsExactly(ClientAuthenticationMethod.NONE);
            verify(jdbc).update(contains("INSERT INTO oauth2_cimd_clients"), eq(client.getId()), eq(URL), any(), any());
            verify(delegate, never()).findByClientId(anyString());
        }

        @Test
        @DisplayName("findById maps the synthetic id back to the URL")
        void byId() {
            String id = CimdRegisteredClientRepository.idFor(URL);
            when(jdbc.query(anyString(), any(RowMapper.class), eq(id))).thenReturn(List.of(URL));
            when(documents.fetch(URL)).thenReturn(doc);
            assertThat(repository.findById(id)).isNotNull();
            when(jdbc.query(anyString(), any(RowMapper.class), eq("cimd:unknown"))).thenReturn(List.of());
            assertThat(repository.findById("cimd:unknown")).isNull();
        }

        @Test
        @DisplayName("an invalid or unreachable document yields no client")
        void rejected() {
            when(documents.fetch(URL)).thenThrow(new ClientMetadataDocumentException(Reason.INVALID, "bad"));
            assertThat(repository.findByClientId(URL)).isNull();
            verify(jdbc, never()).update(anyString(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("trust follows the configured hosts")
        void trusted() {
            when(documents.fetch(URL)).thenReturn(doc);
            RegisteredClient client = repository.findByClientId(URL);
            assertThat(repository.isTrusted(client)).isTrue();
            props.getCimd().setTrustedHosts(List.of("other.example"));
            assertThat(repository.isTrusted(client)).isFalse();
            assertThat(repository.isTrusted(null)).isFalse();
        }

        @Test
        @DisplayName("CIMD clients are never stored")
        void notStored() {
            when(documents.fetch(URL)).thenReturn(doc);
            RegisteredClient client = repository.findByClientId(URL);
            assertThatThrownBy(() -> repository.save(client)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("plain client ids")
    class Plain {

        @Test
        @DisplayName("go to the JDBC repository")
        void delegates() {
            RegisteredClient stored = RegisteredClients.publicClient("uuid", "abc", "App", List.of("https://a/b"),
                    Instant.EPOCH, props);
            when(delegate.findByClientId("abc")).thenReturn(stored);
            when(delegate.findById("uuid")).thenReturn(stored);
            assertThat(repository.findByClientId("abc")).isSameAs(stored);
            assertThat(repository.findById("uuid")).isSameAs(stored);
            repository.save(stored);
            verify(delegate).save(stored);
            assertThat(repository.isTrusted(stored)).isFalse();
        }
    }
}
