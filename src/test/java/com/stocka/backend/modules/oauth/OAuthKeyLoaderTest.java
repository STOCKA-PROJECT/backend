package com.stocka.backend.modules.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;

@DisplayName("OAuthKeyLoader")
class OAuthKeyLoaderTest {

    static String pem(int bits) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(bits);
        KeyPair pair = generator.generateKeyPair();
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(pair.getPrivate().getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----\n";
    }

    @Nested
    @DisplayName("parse")
    class Parse {

        @Test
        @DisplayName("a PKCS#8 PEM yields a signing RSA key with public part and kid")
        void parsesPem() throws Exception {
            RSAKey key = OAuthKeyLoader.parse(pem(2048), "k1");
            assertThat(key.getKeyID()).isEqualTo("k1");
            assertThat(key.isPrivate()).isTrue();
            assertThat(key.toRSAPublicKey()).isNotNull();
            assertThat(key.getAlgorithm().getName()).isEqualTo("RS256");
        }

        @Test
        @DisplayName("literal \\n sequences (as in an env var) are accepted")
        void escapedNewlines() throws Exception {
            String escaped = pem(2048).replace("\n", "\\n");
            assertThat(OAuthKeyLoader.parse(escaped, "k").isPrivate()).isTrue();
        }

        @Test
        @DisplayName("short keys are refused")
        void shortKey() throws Exception {
            assertThatThrownBy(() -> OAuthKeyLoader.parse(pem(1024), "k")).hasMessageContaining("2048");
        }

        @Test
        @DisplayName("garbage is refused with a clear message")
        void garbage() {
            assertThatThrownBy(() -> OAuthKeyLoader.parse("not a key", "k")).hasMessageContaining("private-key");
            assertThatThrownBy(() -> OAuthKeyLoader.parse("-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----", "k"))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("load")
    class Load {

        @Test
        @DisplayName("without a key outside production an ephemeral key is generated")
        void ephemeral() {
            OAuthServerProperties.Jwk jwk = new OAuthServerProperties.Jwk();
            JWKSet set = OAuthKeyLoader.load(jwk, false);
            assertThat(set.getKeys()).hasSize(1);
            assertThat(set.getKeys().get(0).getKeyID()).isEqualTo("stocka-1");
        }

        @Test
        @DisplayName("without a key in production startup fails")
        void productionRequiresKey() {
            assertThatThrownBy(() -> OAuthKeyLoader.load(new OAuthServerProperties.Jwk(), true))
                    .hasMessageContaining("OAUTH_JWK_PRIVATE_KEY");
        }

        @Test
        @DisplayName("the previous key is published after the current one for rotation")
        void rotation() throws Exception {
            OAuthServerProperties.Jwk jwk = new OAuthServerProperties.Jwk();
            jwk.setPrivateKey(pem(2048));
            jwk.setKid("new");
            jwk.setPreviousPrivateKey(pem(2048));
            jwk.setPreviousKid("old");
            JWKSet set = OAuthKeyLoader.load(jwk, true);
            assertThat(set.getKeys()).extracting(k -> k.getKeyID()).containsExactly("new", "old");
        }
    }
}
