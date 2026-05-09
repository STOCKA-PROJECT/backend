package com.stocka.backend.modules.security.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

class JwtServiceTest {

    private static final String VALID_SECRET =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Nested
    @DisplayName("validateConfiguration")
    class ValidateConfiguration {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t"})
        @DisplayName("rejects blank secret")
        void rejectsBlankSecret(String value) {
            JwtService service = newServiceWithSecret(value);

            assertThatThrownBy(service::validateConfiguration)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("security.jwt.secret-key is required");
        }

        @Test
        @DisplayName("rejects secret shorter than 256 bits (64 hex chars)")
        void rejectsShortSecret() {
            JwtService service = newServiceWithSecret("0123456789abcdef");

            assertThatThrownBy(service::validateConfiguration)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("at least 64 hex characters");
        }

        @Test
        @DisplayName("rejects non-hexadecimal secret")
        void rejectsNonHexSecret() {
            String notHex = "z".repeat(64);
            JwtService service = newServiceWithSecret(notHex);

            assertThatThrownBy(service::validateConfiguration)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("hexadecimal");
        }

        @Test
        @DisplayName("rejects secret with odd hex length")
        void rejectsOddLengthSecret() {
            String oddHex = "a".repeat(65);
            JwtService service = newServiceWithSecret(oddHex);

            assertThatThrownBy(service::validateConfiguration)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("hexadecimal");
        }

        @Test
        @DisplayName("accepts a valid 256-bit hex secret")
        void acceptsValidSecret() {
            JwtService service = newServiceWithSecret(VALID_SECRET);

            assertThatCode(service::validateConfiguration).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("accepts uppercase hex characters")
        void acceptsUppercaseHex() {
            JwtService service = newServiceWithSecret(VALID_SECRET.toUpperCase());

            assertThatCode(service::validateConfiguration).doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("getExpirationTime returns the configured value")
    void getExpirationTimeReturnsConfiguredValue() {
        JwtService service = newServiceWithSecret(VALID_SECRET);
        ReflectionTestUtils.setField(service, "jwtExpiration", 60_000L);

        assertThat(service.getExpirationTime()).isEqualTo(60_000L);
    }

    private static JwtService newServiceWithSecret(String secret) {
        JwtService service = new JwtService();
        ReflectionTestUtils.setField(service, "secretKey", secret);
        return service;
    }
}
