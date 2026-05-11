package com.stocka.backend.modules.notifications.email;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("EmailHeaders.safeHeader")
class EmailHeadersTest {

    @Test
    @DisplayName("returns null when the input is null")
    void should_returnNull_when_inputIsNull() {
        assertThat(EmailHeaders.safeHeader(null)).isNull();
    }

    @Test
    @DisplayName("returns a clean value unchanged")
    void should_returnSameValue_when_noControlChars() {
        assertThat(EmailHeaders.safeHeader("Invitation to join Stocka"))
                .isEqualTo("Invitation to join Stocka");
    }

    @Test
    @DisplayName("trims leading and trailing whitespace")
    void should_trimSurroundingWhitespace() {
        assertThat(EmailHeaders.safeHeader("  hi  ")).isEqualTo("hi");
    }

    @Test
    @DisplayName("replaces a CRLF injection in a display name with spaces")
    void should_stripCrlf_when_payloadIsBobBcc() {
        // attacker-controlled display name attempting to smuggle a Bcc header
        assertThat(EmailHeaders.safeHeader("bob\r\nBcc: x@y"))
                .isEqualTo("bob  Bcc: x@y");
    }

    @Test
    @DisplayName("replaces a lone CR with a space")
    void should_stripCr() {
        assertThat(EmailHeaders.safeHeader("bob\rBcc: x@y"))
                .isEqualTo("bob Bcc: x@y");
    }

    @Test
    @DisplayName("replaces a lone LF with a space")
    void should_stripLf() {
        assertThat(EmailHeaders.safeHeader("bob\nBcc: x@y"))
                .isEqualTo("bob Bcc: x@y");
    }

    @Test
    @DisplayName("does not let any CR or LF survive in a long malicious payload")
    void should_neverContainCrLf_when_payloadIsMultilineInjection() {
        String malicious = "Joan\r\nBcc: leak@evil.com\r\nSubject: pwned";

        String sanitized = EmailHeaders.safeHeader(malicious);

        assertThat(sanitized).doesNotContain("\r").doesNotContain("\n");
    }
}
