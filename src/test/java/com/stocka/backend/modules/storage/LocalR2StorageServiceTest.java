package com.stocka.backend.modules.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("LocalR2StorageService")
class LocalR2StorageServiceTest {

    private R2Properties properties;
    private LocalR2StorageService sut;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        properties = new R2Properties();
        properties.setUseLocal(true);
        properties.setLocalDir(tempDir.toString());
        properties.setPresignedTtlMinutes(5);
        sut = new LocalR2StorageService(properties);
    }

    @Nested
    @DisplayName("upload")
    class Upload {
        @Test
        @DisplayName("should write file to disk and return UploadedObject with actual size")
        void should_writeFile_andReturnMetadata() throws IOException {
            byte[] payload = "hello".getBytes(StandardCharsets.UTF_8);
            UploadedObject out = sut.upload("a/b/c.txt", new ByteArrayInputStream(payload), payload.length, "text/plain");
            assertThat(out.key()).isEqualTo("a/b/c.txt");
            assertThat(out.sizeBytes()).isEqualTo(5);
            assertThat(out.mimeType()).isEqualTo("text/plain");
            assertThat(Files.readString(sut.resolve("a/b/c.txt"))).isEqualTo("hello");
        }

        @Test
        @DisplayName("should refuse keys that escape the local dir")
        void should_refuseTraversalKeys() {
            byte[] payload = new byte[]{1, 2, 3};
            assertThatThrownBy(() -> sut.upload("../escape", new ByteArrayInputStream(payload), payload.length, "x"))
                    .isInstanceOf(R2UnavailableException.class);
        }
    }

    @Nested
    @DisplayName("presign")
    class Presign {
        @Test
        @DisplayName("should return URL under /dev/r2/ with TTL in the future")
        void should_returnLocalUrl() {
            PresignedDownload out = sut.presign("org/1/file.png", Duration.ofMinutes(5));
            assertThat(out.url()).startsWith("/dev/r2/").contains("org/1/file.png");
            assertThat(out.expiresAt()).isAfterOrEqualTo(java.time.Instant.now());
        }
    }

    @Nested
    @DisplayName("delete")
    class Delete {
        @Test
        @DisplayName("should remove the underlying file when present")
        void should_removeFile() throws IOException {
            byte[] payload = "x".getBytes();
            sut.upload("k.txt", new ByteArrayInputStream(payload), 1, "text/plain");
            sut.delete("k.txt");
            assertThat(Files.exists(sut.resolve("k.txt"))).isFalse();
        }

        @Test
        @DisplayName("should be a no-op when the file does not exist")
        void should_notFail_whenMissing() {
            sut.delete("never-uploaded.txt");
        }
    }

    @Nested
    @DisplayName("exists")
    class Exists {
        @Test
        @DisplayName("should return true after upload and false after delete")
        void should_track_lifecycle() throws IOException {
            assertThat(sut.exists("k.txt")).isFalse();
            sut.upload("k.txt", new ByteArrayInputStream("x".getBytes()), 1, "text/plain");
            assertThat(sut.exists("k.txt")).isTrue();
            sut.delete("k.txt");
            assertThat(sut.exists("k.txt")).isFalse();
        }
    }

    @Nested
    @DisplayName("buildPieceKey")
    class BuildPieceKey {
        @Test
        @DisplayName("should sanitize unsafe characters and prefix with org/piece path")
        void should_sanitize() {
            String key = sut.buildPieceKey(7, 13, "Foto Final.JPG");
            assertThat(key).startsWith("org/7/piece/13/");
            assertThat(key).endsWith("-foto_final.jpg");
        }

        @Test
        @DisplayName("should fall back to 'file' when name is blank")
        void should_useFallback_whenNameBlank() {
            String key = sut.buildPieceKey(1, 2, "");
            assertThat(key).endsWith("-file");
        }
    }
}
