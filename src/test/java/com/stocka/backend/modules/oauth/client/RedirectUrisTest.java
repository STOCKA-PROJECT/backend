package com.stocka.backend.modules.oauth.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("RedirectUris")
class RedirectUrisTest {

    @Nested
    @DisplayName("registrable")
    class Registrable {

        @ParameterizedTest
        @ValueSource(strings = {"https://claude.ai/api/mcp/auth_callback", "http://localhost:3118/callback",
                "http://127.0.0.1/callback", "http://[::1]:5000/cb", "HTTPS://Example.com/x"})
        @DisplayName("https and loopback http are accepted")
        void accepted(String uri) {
            assertThat(RedirectUris.isRegistrable(uri)).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {"http://evil.example/cb", "https://ok.example/cb#frag", "ftp://x.example/", "not a uri",
                "https:///nohost", "", "custom://callback"})
        @DisplayName("plain http, fragments, other schemes and garbage are rejected")
        void rejected(String uri) {
            assertThat(RedirectUris.isRegistrable(uri)).isFalse();
        }

        @Test
        @DisplayName("null is rejected")
        void nullValue() {
            assertThat(RedirectUris.isRegistrable(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("matching")
    class Matching {

        @Test
        @DisplayName("https requires exact string equality")
        void exact() {
            assertThat(RedirectUris.matches("https://a.example/cb", "https://a.example/cb")).isTrue();
            assertThat(RedirectUris.matches("https://a.example/cb", "https://a.example/cb/")).isFalse();
            assertThat(RedirectUris.matches("https://a.example/cb", "https://A.example/cb")).isFalse();
        }

        @Test
        @DisplayName("loopback ignores the port but not the path")
        void loopbackPort() {
            assertThat(RedirectUris.matches("http://localhost/callback", "http://localhost:51234/callback")).isTrue();
            assertThat(RedirectUris.matches("http://127.0.0.1/callback", "http://127.0.0.1:8080/callback")).isTrue();
            assertThat(RedirectUris.matches("http://localhost:3118/callback", "http://localhost:9999/callback")).isTrue();
            assertThat(RedirectUris.matches("http://localhost/callback", "http://localhost:51234/other")).isFalse();
        }

        @Test
        @DisplayName("localhost and 127.0.0.1 are not interchangeable with each other or with remote hosts")
        void hosts() {
            assertThat(RedirectUris.matches("http://localhost/cb", "http://127.0.0.1/cb")).isFalse();
            assertThat(RedirectUris.matches("http://localhost/cb", "http://evil.example/cb")).isFalse();
        }

        @Test
        @DisplayName("null never matches")
        void nulls() {
            assertThat(RedirectUris.matches(null, "x")).isFalse();
            assertThat(RedirectUris.matches("x", null)).isFalse();
        }
    }

    @Test
    @DisplayName("loopback hosts")
    void loopbackHosts() {
        assertThat(RedirectUris.isLoopbackHost("localhost")).isTrue();
        assertThat(RedirectUris.isLoopbackHost("LOCALHOST")).isTrue();
        assertThat(RedirectUris.isLoopbackHost("127.0.0.1")).isTrue();
        assertThat(RedirectUris.isLoopbackHost("[::1]")).isTrue();
        assertThat(RedirectUris.isLoopbackHost("example.com")).isFalse();
        assertThat(RedirectUris.isLoopbackHost(null)).isFalse();
    }
}
