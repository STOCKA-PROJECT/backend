package com.stocka.backend.modules.notifications.email;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.stocka.backend.modules.users.entity.Language;

@ExtendWith(MockitoExtension.class)
@DisplayName("LocalEmailService")
class LocalEmailServiceTest {

    @Mock private EmailTemplateRenderer renderer;

    @TempDir Path tempDir;

    private LocalEmailService sut;

    @BeforeEach
    void setUp() {
        sut = new LocalEmailService(renderer, "test@stocka.local", tempDir.toString());
        lenient().when(renderer.render(anyString(), anyString(), any(), any(Locale.class), any()))
                .thenReturn(new RenderedEmail("subject", "<html>body</html>"));
    }

    @Nested
    @DisplayName("sendInvitationEmail")
    class SendInvitationEmail {

        @Test
        @DisplayName("should write a file prefixed with 'invitation-' to the configured directory")
        void should_writeFile() throws IOException {
            sut.sendInvitationEmail("inv@test.com", "Joan", "Acme", "http://x/y", Language.ES);

            try (Stream<Path> files = Files.list(tempDir)) {
                long count = files
                        .filter(p -> p.getFileName().toString().startsWith("invitation-"))
                        .filter(p -> p.getFileName().toString().contains("inv@test.com"))
                        .count();
                assertTrue(count >= 1);
            }
        }

        @Test
        @DisplayName("should call the renderer with template name 'invitation', subject key, and the right variables")
        void should_callRenderer_withInvitationTemplate() {
            ArgumentCaptor<Map<String, Object>> varsCaptor = templateVarsCaptor();
            ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);

            sut.sendInvitationEmail("inv@test.com", "Joan", "Acme", "http://x/y", Language.ES);

            verify(renderer).render(
                    eq("invitation"),
                    eq("email.invitation.subject"),
                    argsCaptor.capture(),
                    eq(Locale.of("es")),
                    varsCaptor.capture()
            );
            Map<String, Object> vars = varsCaptor.getValue();
            assertEquals("Joan", vars.get("inviterName"));
            assertEquals("Acme", vars.get("orgName"));
            assertEquals("http://x/y", vars.get("acceptUrl"));

            Object[] subjectArgs = argsCaptor.getValue();
            assertNotNull(subjectArgs);
            assertEquals(2, subjectArgs.length);
            assertEquals("Joan", subjectArgs[0]);
            assertEquals("Acme", subjectArgs[1]);
        }

        @Test
        @DisplayName("should pass the EN locale to the renderer when language is EN")
        void should_passLocaleEn_when_languageIsEn() {
            ArgumentCaptor<Locale> localeCaptor = ArgumentCaptor.forClass(Locale.class);

            sut.sendInvitationEmail("inv@test.com", "Joan", "Acme", "http://x/y", Language.EN);

            verify(renderer).render(anyString(), anyString(), any(), localeCaptor.capture(), any());
            assertEquals(Locale.ENGLISH, localeCaptor.getValue());
        }

        @Test
        @DisplayName("should pass the CA locale to the renderer when language is CA")
        void should_passLocaleCa_when_languageIsCa() {
            ArgumentCaptor<Locale> localeCaptor = ArgumentCaptor.forClass(Locale.class);

            sut.sendInvitationEmail("inv@test.com", "Joan", "Acme", "http://x/y", Language.CA);

            verify(renderer).render(anyString(), anyString(), any(), localeCaptor.capture(), any());
            assertEquals(Locale.of("ca"), localeCaptor.getValue());
        }

        @Test
        @DisplayName("should write a file regardless of language (EN)")
        void should_writeFile_when_languageIsEn() throws IOException {
            sut.sendInvitationEmail("inv@test.com", "Joan", "Acme", "http://x/y", Language.EN);

            try (Stream<Path> files = Files.list(tempDir)) {
                long count = files
                        .filter(p -> p.getFileName().toString().startsWith("invitation-"))
                        .count();
                assertTrue(count >= 1);
            }
        }

        @Test
        @DisplayName("should not throw if the directory cannot be written")
        void should_notThrow_when_writeFails() {
            LocalEmailService bad = new LocalEmailService(renderer, "from@test.local", "\0/invalid");
            assertDoesNotThrow(() -> bad.sendInvitationEmail("x@test.com", "i", "o", "u", Language.ES));
        }
    }

    @Nested
    @DisplayName("sendPasswordResetEmail")
    class SendPasswordResetEmail {

        @Test
        @DisplayName("should write a file prefixed with 'password-reset-' to the configured directory")
        void should_writeFile_withPasswordResetPrefix() throws IOException {
            sut.sendPasswordResetEmail("reset@test.com", "Joan", "http://x/reset?token=abc", Language.ES);

            try (Stream<Path> files = Files.list(tempDir)) {
                long count = files
                        .filter(p -> p.getFileName().toString().startsWith("password-reset-"))
                        .filter(p -> p.getFileName().toString().contains("reset@test.com"))
                        .count();
                assertTrue(count >= 1, "expected one password-reset file in " + tempDir);
            }
        }

        @Test
        @DisplayName("should call the renderer with template name 'password-reset', subject key, and the right variables")
        void should_callRenderer_withPasswordResetTemplate() {
            ArgumentCaptor<Map<String, Object>> varsCaptor = templateVarsCaptor();

            sut.sendPasswordResetEmail("reset@test.com", "Joan", "http://x/reset?token=abc", Language.ES);

            verify(renderer).render(
                    eq("password-reset"),
                    eq("email.passwordReset.subject"),
                    any(),
                    eq(Locale.of("es")),
                    varsCaptor.capture()
            );
            Map<String, Object> vars = varsCaptor.getValue();
            assertEquals("Joan", vars.get("userName"));
            assertEquals("http://x/reset?token=abc", vars.get("resetUrl"));
        }

        @Test
        @DisplayName("should pass the EN locale to the renderer when language is EN")
        void should_passLocaleEn_when_languageIsEn() {
            ArgumentCaptor<Locale> localeCaptor = ArgumentCaptor.forClass(Locale.class);

            sut.sendPasswordResetEmail("reset@test.com", "Joan", "http://x", Language.EN);

            verify(renderer).render(anyString(), anyString(), any(), localeCaptor.capture(), any());
            assertEquals(Locale.ENGLISH, localeCaptor.getValue());
        }

        @Test
        @DisplayName("should pass the CA locale to the renderer when language is CA")
        void should_passLocaleCa_when_languageIsCa() {
            ArgumentCaptor<Locale> localeCaptor = ArgumentCaptor.forClass(Locale.class);

            sut.sendPasswordResetEmail("reset@test.com", "Joan", "http://x", Language.CA);

            verify(renderer).render(anyString(), anyString(), any(), localeCaptor.capture(), any());
            assertEquals(Locale.of("ca"), localeCaptor.getValue());
        }

        @Test
        @DisplayName("written file should contain the rendered html body")
        void should_writeRenderedBody() throws IOException {
            when(renderer.render(eq("password-reset"), anyString(), any(), any(Locale.class), any()))
                    .thenReturn(new RenderedEmail("Restablece tu contraseña en Stocka", "<html>RESET-CONTENT</html>"));

            sut.sendPasswordResetEmail("reset@test.com", "Joan", "http://x/reset?token=abc", Language.ES);

            try (Stream<Path> files = Files.list(tempDir)) {
                Path file = files
                        .filter(p -> p.getFileName().toString().startsWith("password-reset-"))
                        .findFirst()
                        .orElseThrow();
                String content = Files.readString(file);
                assertTrue(content.contains("RESET-CONTENT"));
                assertTrue(content.contains("Restablece tu contraseña en Stocka"),
                        "subject must be embedded in the file as a comment");
            }
        }

        @Test
        @DisplayName("should not throw if the directory cannot be written")
        void should_notThrow_when_writeFails() {
            LocalEmailService bad = new LocalEmailService(renderer, "from@test.local", "\0/invalid");
            assertDoesNotThrow(() -> bad.sendPasswordResetEmail("x@test.com", "Joan", "http://x", Language.ES));
        }
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Map<String, Object>> templateVarsCaptor() {
        return ArgumentCaptor.forClass(Map.class);
    }
}
