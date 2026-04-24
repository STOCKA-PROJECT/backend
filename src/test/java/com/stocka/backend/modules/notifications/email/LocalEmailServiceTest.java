package com.stocka.backend.modules.notifications.email;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
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
        when(renderer.render(anyString(), anyString(), any()))
                .thenReturn(new RenderedEmail("subject", "<html>body</html>"));
    }

    @Test
    @DisplayName("should write a file to the configured directory")
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
    @DisplayName("should not throw if the directory cannot be written")
    void should_notThrow_when_writeFails() {
        LocalEmailService bad = new LocalEmailService(renderer, "from@test.local", "\0/invalid");
        assertDoesNotThrow(() -> bad.sendInvitationEmail("x@test.com", "i", "o", "u"));
    }
}
