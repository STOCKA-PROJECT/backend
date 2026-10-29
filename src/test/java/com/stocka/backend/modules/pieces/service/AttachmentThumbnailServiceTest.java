package com.stocka.backend.modules.pieces.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests of the thumbnail generator: sizes, formats, transparency, EXIF orientation and
 * the failure modes. Images are built in memory with ImageIO; the WebP sample is the 1x1 lossy
 * file browsers use for feature detection.
 */
@DisplayName("AttachmentThumbnailService")
class AttachmentThumbnailServiceTest {
    /** 1x1 lossy WebP (Modernizr's feature-detection image). */
    private static final byte[] WEBP_1X1 = Base64.getDecoder()
            .decode("UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA");

    private AttachmentThumbnailService sut;

    @BeforeEach
    void setUp() {
        sut = service(512);
    }

    private static AttachmentThumbnailService service(int maxSide) {
        PieceAttachmentProperties limits = new PieceAttachmentProperties();
        limits.setThumbnailMaxSide(maxSide);
        return new AttachmentThumbnailService(limits);
    }

    private static byte[] imageBytes(String format, int width, int height, int type, Color fill) {
        try {
            BufferedImage img = new BufferedImage(width, height, type);
            if (fill != null) {
                Graphics2D g = img.createGraphics();
                g.setColor(fill);
                g.fillRect(0, 0, width, height);
                g.dispose();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            assertThat(ImageIO.write(img, format, out)).as("ImageIO writer for " + format).isTrue();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] jpeg(int width, int height) {
        return imageBytes("jpeg", width, height, BufferedImage.TYPE_INT_RGB, Color.RED);
    }

    private static BufferedImage decode(byte[] jpeg) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(jpeg));
            assertThat(img).as("decodable JPEG").isNotNull();
            return img;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Inserts an APP1 Exif segment carrying only the orientation tag into a JPEG written by
     * ImageIO, right after its JFIF APP0 segment (the JDK reader insists on APP0 being the first
     * marker after SOI; phones write Exif there too).
     */
    private static byte[] withExifOrientation(byte[] jpeg, int orientation) {
        byte[] app1 = {
                (byte) 0xFF, (byte) 0xE1, 0x00, 0x22,
                'E', 'x', 'i', 'f', 0, 0,
                'M', 'M', 0, 0x2A, 0, 0, 0, 0x08,
                0, 0x01,
                0x01, 0x12, 0, 0x03, 0, 0, 0, 0x01, 0, (byte) orientation, 0, 0,
                0, 0, 0, 0
        };
        assertThat(jpeg[2]).as("APP0 marker").isEqualTo((byte) 0xFF);
        assertThat(jpeg[3]).as("APP0 marker").isEqualTo((byte) 0xE0);
        int app0Length = ((jpeg[4] & 0xFF) << 8) | (jpeg[5] & 0xFF);
        int insertAt = 4 + app0Length;
        byte[] out = new byte[jpeg.length + app1.length];
        System.arraycopy(jpeg, 0, out, 0, insertAt);
        System.arraycopy(app1, 0, out, insertAt, app1.length);
        System.arraycopy(jpeg, insertAt, out, insertAt + app1.length, jpeg.length - insertAt);
        return out;
    }

    @Nested
    @DisplayName("sizing")
    class Sizing {
        @Test
        @DisplayName("a landscape image is scaled to the max side on its width, keeping the aspect ratio")
        void landscape() throws IOException {
            Thumbnail t = sut.generate(jpeg(1600, 800)).orElseThrow();

            assertThat(t.width()).isEqualTo(512);
            assertThat(t.height()).isEqualTo(256);
            assertThat(t.sourceWidth()).isEqualTo(1600);
            assertThat(t.sourceHeight()).isEqualTo(800);
            BufferedImage decoded = decode(t.bytes());
            assertThat(decoded.getWidth()).isEqualTo(512);
            assertThat(decoded.getHeight()).isEqualTo(256);
        }

        @Test
        @DisplayName("a portrait image is scaled to the max side on its height")
        void portrait() throws IOException {
            Thumbnail t = sut.generate(jpeg(800, 1600)).orElseThrow();

            assertThat(t.width()).isEqualTo(256);
            assertThat(t.height()).isEqualTo(512);
        }

        @Test
        @DisplayName("an image smaller than the max side keeps its size (never upscaled)")
        void small() throws IOException {
            Thumbnail t = sut.generate(jpeg(100, 60)).orElseThrow();

            assertThat(t.width()).isEqualTo(100);
            assertThat(t.height()).isEqualTo(60);
            assertThat(t.sourceWidth()).isEqualTo(100);
            assertThat(t.sourceHeight()).isEqualTo(60);
        }

        @Test
        @DisplayName("an image exactly at the max side keeps its size")
        void exact() throws IOException {
            Thumbnail t = sut.generate(jpeg(512, 384)).orElseThrow();

            assertThat(t.width()).isEqualTo(512);
            assertThat(t.height()).isEqualTo(384);
        }

        @Test
        @DisplayName("a large image goes through the subsampled decode and still lands on the max side")
        void large() throws IOException {
            Thumbnail t = sut.generate(jpeg(3000, 2000)).orElseThrow();

            assertThat(t.width()).isEqualTo(512);
            assertThat(t.height()).isEqualTo(341);
            assertThat(t.sourceWidth()).isEqualTo(3000);
            assertThat(t.sourceHeight()).isEqualTo(2000);
        }

        @Test
        @DisplayName("the max side comes from the properties")
        void configuredMaxSide() throws IOException {
            Thumbnail t = service(128).generate(jpeg(1600, 800)).orElseThrow();

            assertThat(t.width()).isEqualTo(128);
            assertThat(t.height()).isEqualTo(64);
        }

        @Test
        @DisplayName("a very thin image never collapses below one pixel")
        void thin() throws IOException {
            Thumbnail t = sut.generate(jpeg(2000, 1)).orElseThrow();

            assertThat(t.width()).isEqualTo(512);
            assertThat(t.height()).isEqualTo(1);
        }

        @Test
        @DisplayName("the thumbnail is a JPEG and weighs less than the original")
        void jpegOutput() throws IOException {
            byte[] original = jpeg(1600, 1200);
            Thumbnail t = sut.generate(original).orElseThrow();

            assertThat(t.bytes()[0]).isEqualTo((byte) 0xFF);
            assertThat(t.bytes()[1]).isEqualTo((byte) 0xD8);
            assertThat(t.bytes().length).isLessThan(original.length);
        }
    }

    @Nested
    @DisplayName("formats")
    class Formats {
        @Test
        @DisplayName("PNG with transparency is flattened on white")
        void transparentPng() throws IOException {
            byte[] png = imageBytes("png", 400, 200, BufferedImage.TYPE_INT_ARGB, null);

            Thumbnail t = sut.generate(png).orElseThrow();

            BufferedImage decoded = decode(t.bytes());
            Color corner = new Color(decoded.getRGB(0, 0));
            assertThat(corner.getRed()).isGreaterThan(240);
            assertThat(corner.getGreen()).isGreaterThan(240);
            assertThat(corner.getBlue()).isGreaterThan(240);
            assertThat(t.width()).isEqualTo(400);
            assertThat(t.height()).isEqualTo(200);
        }

        @Test
        @DisplayName("opaque PNG keeps its colour")
        void opaquePng() throws IOException {
            byte[] png = imageBytes("png", 64, 64, BufferedImage.TYPE_INT_RGB, Color.BLUE);

            Thumbnail t = sut.generate(png).orElseThrow();

            Color center = new Color(decode(t.bytes()).getRGB(32, 32));
            assertThat(center.getBlue()).isGreaterThan(200);
            assertThat(center.getRed()).isLessThan(60);
        }

        @Test
        @DisplayName("GIF decodes (first frame)")
        void gif() throws IOException {
            byte[] gif = imageBytes("gif", 300, 100, BufferedImage.TYPE_INT_RGB, Color.GREEN);

            Thumbnail t = sut.generate(gif).orElseThrow();

            assertThat(t.width()).isEqualTo(300);
            assertThat(t.height()).isEqualTo(100);
        }

        @Test
        @DisplayName("WebP decodes through the TwelveMonkeys reader")
        void webp() throws IOException {
            Thumbnail t = sut.generate(WEBP_1X1).orElseThrow();

            assertThat(t.width()).isEqualTo(1);
            assertThat(t.height()).isEqualTo(1);
            assertThat(t.sourceWidth()).isEqualTo(1);
            assertThat(decode(t.bytes()).getWidth()).isEqualTo(1);
        }

        @Test
        @DisplayName("bytes no reader understands give an empty result")
        void unsupported() throws IOException {
            Optional<Thumbnail> t = sut.generate("hello, not an image".getBytes(StandardCharsets.UTF_8));

            assertThat(t).isEmpty();
        }

        @Test
        @DisplayName("a JPEG header followed by garbage fails with IOException")
        void corrupt() {
            byte[] corrupt = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xC0, 0, 3, 1, 2, 3, 4, 5, 6};

            assertThatThrownBy(() -> sut.generate(corrupt)).isInstanceOf(IOException.class);
        }

        @Test
        @DisplayName("an empty array gives an empty result")
        void empty() throws IOException {
            assertThat(sut.generate(new byte[0])).isEmpty();
        }
    }

    @Nested
    @DisplayName("EXIF orientation")
    class Exif {
        @Test
        @DisplayName("orientation 6 (rotate 90 clockwise) swaps the axes of the thumbnail and the source size")
        void rotated90() throws IOException {
            byte[] photo = withExifOrientation(jpeg(40, 20), 6);

            Thumbnail t = sut.generate(photo).orElseThrow();

            assertThat(t.width()).isEqualTo(20);
            assertThat(t.height()).isEqualTo(40);
            assertThat(t.sourceWidth()).isEqualTo(20);
            assertThat(t.sourceHeight()).isEqualTo(40);
            assertThat(decode(t.bytes()).getWidth()).isEqualTo(20);
        }

        @Test
        @DisplayName("orientation 8 (rotate 90 counter-clockwise) swaps the axes too, on a scaled image")
        void rotated270Scaled() throws IOException {
            byte[] photo = withExifOrientation(jpeg(1600, 800), 8);

            Thumbnail t = sut.generate(photo).orElseThrow();

            assertThat(t.width()).isEqualTo(256);
            assertThat(t.height()).isEqualTo(512);
            assertThat(t.sourceWidth()).isEqualTo(800);
            assertThat(t.sourceHeight()).isEqualTo(1600);
        }

        @Test
        @DisplayName("orientation 3 (180 degrees) keeps the axes")
        void rotated180() throws IOException {
            Thumbnail t = sut.generate(withExifOrientation(jpeg(40, 20), 3)).orElseThrow();

            assertThat(t.width()).isEqualTo(40);
            assertThat(t.height()).isEqualTo(20);
            assertThat(t.sourceWidth()).isEqualTo(40);
        }

        @Test
        @DisplayName("orientation 1 and no EXIF leave the image as is")
        void upright() throws IOException {
            assertThat(sut.generate(withExifOrientation(jpeg(40, 20), 1)).orElseThrow().width()).isEqualTo(40);
            assertThat(sut.generate(jpeg(40, 20)).orElseThrow().width()).isEqualTo(40);
        }
    }
}
