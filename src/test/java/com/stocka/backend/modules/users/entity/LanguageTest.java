package com.stocka.backend.modules.users.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@DisplayName("Language enum")
class LanguageTest {

    @Nested
    @DisplayName("fromString")
    class FromString {

        @Test
        @DisplayName("should default to ES when input is null")
        void should_returnEs_when_null() {
            assertEquals(Language.ES, Language.fromString(null));
        }

        @Test
        @DisplayName("should default to ES when input is blank")
        void should_returnEs_when_blank() {
            assertEquals(Language.ES, Language.fromString("   "));
        }

        @Test
        @DisplayName("should map 'ES' to Language.ES")
        void should_returnEs_when_es() {
            assertEquals(Language.ES, Language.fromString("ES"));
        }

        @Test
        @DisplayName("should map 'EN' to Language.EN")
        void should_returnEn_when_en() {
            assertEquals(Language.EN, Language.fromString("EN"));
        }

        @Test
        @DisplayName("should map 'CA' to Language.CA")
        void should_returnCa_when_ca() {
            assertEquals(Language.CA, Language.fromString("CA"));
        }

        @Test
        @DisplayName("should accept lowercase 'es'")
        void should_returnEs_when_lowercaseEs() {
            assertEquals(Language.ES, Language.fromString("es"));
        }

        @Test
        @DisplayName("should accept lowercase 'en'")
        void should_returnEn_when_lowercaseEn() {
            assertEquals(Language.EN, Language.fromString("en"));
        }

        @Test
        @DisplayName("should accept lowercase 'ca'")
        void should_returnCa_when_lowercaseCa() {
            assertEquals(Language.CA, Language.fromString("ca"));
        }

        @Test
        @DisplayName("should accept input with surrounding whitespace")
        void should_trim_whitespace() {
            assertEquals(Language.EN, Language.fromString("  en  "));
        }

        @Test
        @DisplayName("should throw 400 with Spanish message when input is invalid")
        void should_throw400_when_invalid() {
            ResponseStatusException ex = assertThrows(
                    ResponseStatusException.class,
                    () -> Language.fromString("XX")
            );
            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
            assertTrue(ex.getReason() != null && ex.getReason().contains("Idioma no soportado"),
                    "expected Spanish error message, got: " + ex.getReason());
        }

        @Test
        @DisplayName("should throw 400 for an unsupported ISO code (FR)")
        void should_throw400_when_unsupportedIsoCode() {
            ResponseStatusException ex = assertThrows(
                    ResponseStatusException.class,
                    () -> Language.fromString("FR")
            );
            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        }
    }

    @Nested
    @DisplayName("toLocale")
    class ToLocale {

        @Test
        @DisplayName("ES → Locale 'es'")
        void es_toLocale() {
            assertEquals(Locale.of("es"), Language.ES.toLocale());
        }

        @Test
        @DisplayName("EN → Locale.ENGLISH")
        void en_toLocale() {
            assertEquals(Locale.ENGLISH, Language.EN.toLocale());
        }

        @Test
        @DisplayName("CA → Locale 'ca'")
        void ca_toLocale() {
            assertEquals(Locale.of("ca"), Language.CA.toLocale());
        }
    }
}
