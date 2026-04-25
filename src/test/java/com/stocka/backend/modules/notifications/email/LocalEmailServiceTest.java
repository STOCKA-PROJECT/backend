package com.stocka.backend.modules.notifications.email;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

@ExtendWith(MockitoExtension.class)
@DisplayName("LocalEmailService")
class LocalEmailServiceTest {

    @Mock private EmailTemplateRenderer renderer;

    @TempDir Path tempDir;

    private LocalEmailService sut;

    @BeforeEach
    void setUp() {
        sut = new LocalEmailService(renderer, "test@stocka.local", tempDir.toString());
        lenient().when(renderer.render(anyString(), anyString(), any()))
                .thenReturn(new RenderedEmail("subject", "<html>body</html>"));
    }

    @Nested
    @DisplayName("sendInvitationEmail")
    class SendInvitationEmail {

        @Test
        @DisplayName("should write a file prefixed with 'invitation-' to the configured directory")
        void should_writeFile() throws IOException {
            sut.sendInvitationEmail("inv@test.com", "Joan", "Acme", "http://x/y");

            try (Stream<Path> files = Files.list(tempDir)) {
                long count = files
                        .filter(p -> p.getFileName().toString().startsWith("invitation-"))
                        .filter(p -> p.getFileName().toString().contains("inv@test.com"))
                        .count();
                assertTrue(count >= 1);
            }
        }

        @Test
        @DisplayName("should call the renderer with template name 'invitation' and the right variables")
        void should_callRenderer_withInvitationTemplate() {
            ArgumentCaptor<Map<String, Object>> varsCaptor = templateVarsCaptor();

            sut.sendInvitationEmail("inv@test.com", "Joan", "Acme", "http://x/y");

            verify(renderer).render(eq("invitation"), anyString(), varsCaptor.capture());
            Map<String, Object> vars = varsCaptor.getValue();
            assertEquals("Joan", vars.get("inviterName"));
            assertEquals("Acme", vars.get("orgName"));
            assertEquals("http://x/y", vars.get("acceptUrl"));
        }

        @Test
        @DisplayName("should not throw if the directory cannot be written")
        void should_notThrow_when_writeFails() {
            LocalEmailService bad = new LocalEmailService(renderer, "from@test.local", "\0/invalid");
            assertDoesNotThrow(() -> bad.sendInvitationEmail("x@test.com", "i", "o", "u"));
        }
    }

    @Nested
    @DisplayName("sendPasswordResetEmail")
    class SendPasswordResetEmail {

        @Test
        @DisplayName("should write a file prefixed with 'password-reset-' to the configured directory")
        void should_writeFile_withPasswordResetPrefix() throws IOException {
            sut.sendPasswordResetEmail("reset@test.com", "Joan", "http://x/reset?token=abc");

            try (Stream<Path> files = Files.list(tempDir)) {
                long count = files
                        .filter(p -> p.getFileName().toString().startsWith("password-reset-"))
                        .filter(p -> p.getFileName().toString().contains("reset@test.com"))
                        .count();
                assertTrue(count >= 1, "expected one password-reset file in " + tempDir);
            }
        }

        @Test
        @DisplayName("should call the renderer with template name 'password-reset' and the right variables")
        void should_callRenderer_withPasswordResetTemplate() {
            ArgumentCaptor<Map<String, Object>> varsCaptor = templateVarsCaptor();

            sut.sendPasswordResetEmail("reset@test.com", "Joan", "http://x/reset?token=abc");

            verify(renderer).render(eq("password-reset"), anyString(), varsCaptor.capture());
            Map<String, Object> vars = varsCaptor.getValue();
            assertEquals("Joan", vars.get("userName"));
            assertEquals("http://x/reset?token=abc", vars.get("resetUrl"));
        }

        @Test
        @DisplayName("should use a Spanish subject for the password-reset email")
        void should_useSpanishSubject() {
            ArgumentCaptor<String> subjectCaptor = ArgumentCaptor.forClass(String.class);

            sut.sendPasswordResetEmail("reset@test.com", "Joan", "http://x");

            verify(renderer).render(anyString(), subjectCaptor.capture(), any());
            assertTrue(subjectCaptor.getValue().toLowerCase().contains("contraseña"),
                    "subject should be in Spanish: " + subjectCaptor.getValue());
        }

        @Test
        @DisplayName("written file should contain the rendered html body")
        void should_writeRenderedBody() throws IOException {
            when(renderer.render(eq("password-reset"), anyString(), any()))
                    .thenReturn(new RenderedEmail("Restablece tu contraseña en Stocka", "<html>RESET-CONTENT</html>"));

            sut.sendPasswordResetEmail("reset@test.com", "Joan", "http://x/reset?token=abc");

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
            assertDoesNotThrow(() -> bad.sendPasswordResetEmail("x@test.com", "Joan", "http://x"));
        }
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Map<String, Object>> templateVarsCaptor() {
        return ArgumentCaptor.forClass(Map.class);
    }
}
