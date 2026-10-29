package com.stocka.backend.modules.pieces.service;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;
import java.util.Optional;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.twelvemonkeys.imageio.stream.ByteArrayImageInputStream;

import net.coobird.thumbnailator.Thumbnails;
import net.coobird.thumbnailator.filters.ImageFilter;
import net.coobird.thumbnailator.util.exif.ExifFilterUtils;
import net.coobird.thumbnailator.util.exif.ExifUtils;
import net.coobird.thumbnailator.util.exif.Orientation;

/**
 * Generates the JPEG thumbnail of an IMAGE attachment.
 *
 * <p>The long side of the result is at most {@link PieceAttachmentProperties#getThumbnailMaxSide()}
 * pixels (smaller images are re-encoded at their own size, never upscaled). The decoder reads the
 * image with source subsampling computed from the header dimensions, so a picture of
 * {@link PieceAttachmentProperties#getMaxImageDimensionPixels()} pixels per side never materializes
 * at full size in the heap: the decoded raster is always below four times the thumbnail side.
 * Phone photos carry their rotation as EXIF orientation; the thumbnail is rotated accordingly, so
 * {@link Thumbnail#sourceWidth()} and {@link Thumbnail#sourceHeight()} are the size as displayed.
 * Transparent pixels (PNG, GIF, WebP) are flattened on white, GIF animations keep their first
 * frame, and WebP is read through the TwelveMonkeys plugin registered in ImageIO.
 */
@Service
public class AttachmentThumbnailService {
    private static final Logger log = LoggerFactory.getLogger(AttachmentThumbnailService.class);

    /**
     * Largest raster, in pixels, the fallback decode without subsampling may produce when a reader
     * rejects {@link ImageReadParam#setSourceSubsampling}: about 160 MB of ARGB.
     */
    private static final long MAX_UNSUBSAMPLED_PIXELS = 40_000_000L;

    private final int maxSide;
    private final float quality;

    public AttachmentThumbnailService(PieceAttachmentProperties limits) {
        this.maxSide = Math.max(16, limits.getThumbnailMaxSide());
        this.quality = Math.min(1f, Math.max(0.1f, limits.getThumbnailQuality()));
        // In a Spring Boot fat jar ImageIO only discovers the TwelveMonkeys WebP reader once the
        // registry is rescanned with the launcher's class loader.
        ImageIO.scanForPlugins();
    }

    /**
     * Decodes {@code bytes} and scales them down to a JPEG thumbnail.
     *
     * @param bytes the original image, as uploaded
     * @return the thumbnail, or empty when no ImageIO reader understands the bytes (for example an
     *         image format without a plugin on the classpath)
     * @throws IOException when the bytes are not a decodable image or the JPEG cannot be written
     */
    public Optional<Thumbnail> generate(byte[] bytes) throws IOException {
        try (ImageInputStream input = new ByteArrayImageInputStream(bytes)) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return Optional.empty();
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, false, false);
                int sourceWidth = reader.getWidth(0);
                int sourceHeight = reader.getHeight(0);
                Orientation orientation = readOrientation(reader);
                BufferedImage decoded = decode(reader, sourceWidth, sourceHeight);
                return Optional.of(scale(decoded, orientation, sourceWidth, sourceHeight));
            } finally {
                reader.dispose();
            }
        }
    }

    /**
     * Reads the image at a reduced resolution when it is much larger than the thumbnail. The
     * subsampling factor keeps the decoded long side between two and four times {@code maxSide}.
     */
    private BufferedImage decode(ImageReader reader, int width, int height) throws IOException {
        int longSide = Math.max(width, height);
        int subsampling = Math.max(1, longSide / (2 * maxSide));
        ImageReadParam param = reader.getDefaultReadParam();
        if (subsampling > 1) {
            param.setSourceSubsampling(subsampling, subsampling, 0, 0);
            try {
                return reader.read(0, param);
            } catch (UnsupportedOperationException | IllegalArgumentException e) {
                long pixels = (long) width * height;
                if (pixels > MAX_UNSUBSAMPLED_PIXELS) {
                    throw new IOException("reader " + reader.getFormatName()
                            + " does not subsample and the image is too large: " + width + "x" + height, e);
                }
                log.debug("thumbnail_subsampling_unsupported format={} reason={}", reader.getFormatName(), e.getMessage());
            }
        }
        return reader.read(0, reader.getDefaultReadParam());
    }

    /** EXIF orientation of a JPEG; other formats (and JPEGs without EXIF) are upright. */
    private static Orientation readOrientation(ImageReader reader) {
        try {
            String format = reader.getFormatName();
            if (format == null) {
                return Orientation.TOP_LEFT;
            }
            String lower = format.toLowerCase(Locale.ROOT);
            if (!lower.equals("jpeg") && !lower.equals("jpg")) {
                return Orientation.TOP_LEFT;
            }
            Orientation orientation = ExifUtils.getExifOrientation(reader, 0);
            return orientation == null ? Orientation.TOP_LEFT : orientation;
        } catch (IOException | RuntimeException e) {
            log.debug("thumbnail_exif_unreadable reason={}", e.getMessage());
            return Orientation.TOP_LEFT;
        }
    }

    private Thumbnail scale(BufferedImage decoded, Orientation orientation, int sourceWidth, int sourceHeight)
            throws IOException {
        int decodedWidth = decoded.getWidth();
        int decodedHeight = decoded.getHeight();
        double factor = Math.min(1d, (double) maxSide / Math.max(decodedWidth, decodedHeight));
        int targetWidth = Math.max(1, (int) Math.round(decodedWidth * factor));
        int targetHeight = Math.max(1, (int) Math.round(decodedHeight * factor));

        BufferedImage scaled = Thumbnails.of(decoded).forceSize(targetWidth, targetHeight).asBufferedImage();
        if (orientation != Orientation.TOP_LEFT) {
            // Applied after scaling (rotating a few hundred pixels is cheap) and outside the
            // builder: a forced size would stretch the rotated image back to the unrotated box.
            ImageFilter filter = ExifFilterUtils.getFilterForOrientation(orientation);
            scaled = filter.apply(scaled);
        }
        BufferedImage flattened = flattenOnWhite(scaled);
        byte[] jpeg = encodeJpeg(flattened);

        boolean axesSwapped = scaled.getWidth() != targetWidth || scaled.getHeight() != targetHeight;
        int displayedWidth = axesSwapped ? sourceHeight : sourceWidth;
        int displayedHeight = axesSwapped ? sourceWidth : sourceHeight;
        return new Thumbnail(jpeg, flattened.getWidth(), flattened.getHeight(), displayedWidth, displayedHeight);
    }

    /** JPEG has no alpha channel: transparent areas become white, like the editor's board. */
    private static BufferedImage flattenOnWhite(BufferedImage image) {
        BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
            g.drawImage(image, 0, 0, null);
        } finally {
            g.dispose();
        }
        return rgb;
    }

    private byte[] encodeJpeg(BufferedImage image) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IOException("No JPEG writer registered in ImageIO");
        }
        ImageWriter writer = writers.next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(out)) {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            writer.setOutput(output);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }
}
