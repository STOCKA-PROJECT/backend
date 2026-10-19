package com.stocka.backend.modules.devices.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.stocka.backend.modules.devices.service.DeviceTokenService.GeneratedToken;

@DisplayName("DeviceTokenService")
class DeviceTokenServiceTest {

    private final DeviceTokenService sut = new DeviceTokenService();

    @Nested
    @DisplayName("generate")
    class Generate {

        @Test
        @DisplayName("should produce a bht_ prefixed, unpadded base64url token of 32 random bytes")
        void generate_should_produceBase64UrlToken() {
            GeneratedToken token = sut.generate();

            assertThat(token.token()).startsWith(DeviceTokenService.TOKEN_PREFIX);
            String body = token.token().substring(DeviceTokenService.TOKEN_PREFIX.length());
            assertThat(body).hasSize(43).matches("[A-Za-z0-9_-]+");
            assertThat(token.token()).doesNotContain("=", "+", "/");
        }

        @Test
        @DisplayName("should derive hash and prefix from the token")
        void generate_should_deriveHashAndPrefix() {
            GeneratedToken token = sut.generate();

            assertThat(token.hash()).isEqualTo(sut.hash(token.token()));
            assertThat(token.prefix()).isEqualTo(token.token().substring(0, DeviceTokenService.PREFIX_LENGTH));
            assertThat(token.prefix()).startsWith("bht_").hasSize(8);
        }

        @Test
        @DisplayName("should produce distinct tokens on every call")
        void generate_should_beRandom() {
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < 50; i++) {
                assertThat(seen.add(sut.generate().token())).isTrue();
            }
        }
    }

    @Nested
    @DisplayName("hash")
    class Hash {

        @Test
        @DisplayName("should be the lowercase sha256 hex of the UTF-8 token")
        void hash_should_beSha256Hex() {
            assertThat(sut.hash("bht_test"))
                    .hasSize(64)
                    .matches("[0-9a-f]{64}");
        }

        @Test
        @DisplayName("should be deterministic and sensitive to a single character")
        void hash_should_beDeterministic() {
            assertThat(sut.hash("bht_abc")).isEqualTo(sut.hash("bht_abc"));
            assertThat(sut.hash("bht_abc")).isNotEqualTo(sut.hash("bht_abd"));
        }

        @Test
        @DisplayName("should match a known vector")
        void hash_should_matchKnownVector() {
            // sha256("abc") from FIPS 180-2
            assertThat(sut.hash("abc"))
                    .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        }
    }

    @Nested
    @DisplayName("prefixOf")
    class PrefixOf {

        @Test
        @DisplayName("should take the first 8 characters")
        void prefixOf_should_takeFirstEight() {
            assertThat(DeviceTokenService.prefixOf("bht_0123456789")).isEqualTo("bht_0123");
        }

        @Test
        @DisplayName("should return the whole token when it is shorter than the prefix")
        void prefixOf_should_returnWholeShortToken() {
            assertThat(DeviceTokenService.prefixOf("bht_1")).isEqualTo("bht_1");
            assertThat(DeviceTokenService.prefixOf("bht_1234")).isEqualTo("bht_1234");
        }
    }
}
