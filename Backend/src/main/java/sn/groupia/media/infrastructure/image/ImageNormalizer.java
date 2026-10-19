package sn.groupia.media.infrastructure.image;

import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifReader;
import com.twelvemonkeys.image.ResampleOp;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Semaphore;
import javax.imageio.*;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.springframework.stereotype.Component;
import sn.groupia.media.infrastructure.MediaProperties;
import sn.groupia.media.infrastructure.storage.FilesystemMediaStorage;
import sn.groupia.shared.api.ApiException;

@Component
public final class ImageNormalizer {
    private final MediaProperties limits;
    private final Semaphore processing;

    public ImageNormalizer(MediaProperties limits) {
        this.limits = limits;
        processing = new Semaphore(1);
        if (limits.enabled()) requireReaders();
    }

    public static void requireReaders() {
        for (String format : java.util.List.of("JPEG", "PNG", "WebP"))
            if (!ImageIO.getImageReadersByFormatName(format).hasNext())
                throw new IllegalStateException("Required image reader unavailable: " + format);
    }

    public record Rendition(
            byte[] bytes, String contentType, int width, int height, String sha256) {}

    public record Normalized(String fingerprint, Rendition display, Rendition thumbnail) {}

    public Normalized normalize(InputStream source, String declaredType) {
        if (!processing.tryAcquire()) throw error(503, "MEDIA_PROCESSING_BUSY");
        try {
            byte[] bytes = source.readNBytes(limits.maxUploadBytes() + 1);
            if (bytes.length > limits.maxUploadBytes()) throw error(413, "MEDIA_TOO_LARGE");
            String type = detect(bytes);
            if (!type.equals(declaredType)) throw error(415, "MEDIA_CONTENT_TYPE_MISMATCH");
            byte[] exif = validateContainer(bytes, type);
            int orientation = orientation(exif);
            try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
                var readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) throw error(422, "MEDIA_INVALID_IMAGE");
                ImageReader reader = readers.next();
                try {
                    reader.setInput(input, true, true);
                    int width = reader.getWidth(0), height = reader.getHeight(0);
                    if (width < 1
                            || height < 1
                            || width > limits.maxWidth()
                            || height > limits.maxHeight()
                            || (long) width * height > limits.maxPixels())
                        throw error(422, "MEDIA_DIMENSIONS_EXCEEDED");
                    BufferedImage decoded = reader.read(0);
                    if (decoded == null) throw error(422, "MEDIA_INVALID_IMAGE");
                    BufferedImage pixels = orient(decoded, orientation);
                    return new Normalized(
                            FilesystemMediaStorage.sha(bytes),
                            encode(pixels, limits.displayMaxSide(), limits.displayMaxBytes()),
                            encode(pixels, limits.thumbnailMaxSide(), limits.thumbnailMaxBytes()));
                } finally {
                    reader.dispose();
                }
            }
        } catch (ApiException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw error(422, "MEDIA_INVALID_IMAGE");
        } finally {
            processing.release();
        }
    }

    private String detect(byte[] bytes) {
        if (bytes.length >= 4 && (bytes[0] & 255) == 255 && (bytes[1] & 255) == 216)
            return "image/jpeg";
        if (bytes.length >= 8
                && Arrays.equals(
                        Arrays.copyOf(bytes, 8),
                        new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10})) return "image/png";
        if (bytes.length >= 12
                && ascii(bytes, 0, 4).equals("RIFF")
                && ascii(bytes, 8, 4).equals("WEBP")) return "image/webp";
        throw error(415, "MEDIA_UNSUPPORTED_FORMAT");
    }

    /** Inspect bounded container chunks, never inflate PNG text/ICC metadata. */
    private byte[] validateContainer(byte[] bytes, String type) {
        byte[] exif = null;
        if (type.equals("image/jpeg")) {
            if (bytes[bytes.length - 2] != (byte) 255 || bytes[bytes.length - 1] != (byte) 217)
                throw error(422, "MEDIA_INVALID_IMAGE");
            int p = 2;
            while (p + 4 <= bytes.length) {
                if (bytes[p++] != (byte) 255) throw error(422, "MEDIA_INVALID_IMAGE");
                while (p < bytes.length && bytes[p] == (byte) 255) p++;
                int marker = bytes[p++] & 255;
                if (marker == 218 || marker == 217) break;
                int length = Short.toUnsignedInt(ByteBuffer.wrap(bytes, p, 2).getShort());
                if (length < 2 || p + length > bytes.length)
                    throw error(422, "MEDIA_INVALID_IMAGE");
                if (marker == 225 && length >= 8 && ascii(bytes, p + 2, 6).equals("Exif\0\0"))
                    exif = Arrays.copyOfRange(bytes, p + 2, p + length);
                p += length;
            }
        } else {
            boolean png = type.equals("image/png");
            int p = png ? 8 : 12;
            if (!png
                    && Integer.toUnsignedLong(
                                            ByteBuffer.wrap(bytes, 4, 4)
                                                    .order(ByteOrder.LITTLE_ENDIAN)
                                                    .getInt())
                                    + 8
                            != bytes.length) throw error(422, "MEDIA_INVALID_IMAGE");
            boolean ended = false;
            while (p < bytes.length) {
                if (p + (png ? 12 : 8) > bytes.length) throw error(422, "MEDIA_INVALID_IMAGE");
                long length =
                        Integer.toUnsignedLong(
                                ByteBuffer.wrap(bytes, p + (png ? 0 : 4), 4)
                                        .order(png ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN)
                                        .getInt());
                String chunk = ascii(bytes, p + (png ? 4 : 0), 4);
                long end = p + 8L + length + (png ? 4 : length % 2);
                if (end > bytes.length) throw error(422, "MEDIA_INVALID_IMAGE");
                if (chunk.equals("acTL")
                        || chunk.equals("ANIM")
                        || chunk.equals("ANMF")
                        || (chunk.equals("VP8X") && length > 0 && (bytes[p + 8] & 2) != 0))
                    throw error(415, "MEDIA_ANIMATION_UNSUPPORTED");
                if (chunk.equals("eXIf") || chunk.equals("EXIF")) {
                    byte[] raw = Arrays.copyOfRange(bytes, p + 8, p + 8 + (int) length);
                    if (raw.length >= 6 && ascii(raw, 0, 6).equals("Exif\0\0")) exif = raw;
                    else {
                        exif = new byte[raw.length + 6];
                        System.arraycopy(
                                "Exif\0\0".getBytes(StandardCharsets.US_ASCII), 0, exif, 0, 6);
                        System.arraycopy(raw, 0, exif, 6, raw.length);
                    }
                }
                p = (int) end;
                if (chunk.equals("IEND")) {
                    ended = true;
                    if (p != bytes.length) throw error(422, "MEDIA_INVALID_IMAGE");
                }
            }
            if (png && !ended) throw error(422, "MEDIA_INVALID_IMAGE");
        }
        return exif;
    }

    private static String ascii(byte[] b, int start, int count) {
        return new String(b, start, count, StandardCharsets.US_ASCII);
    }

    private int orientation(byte[] exif) {
        if (exif == null) return 1;
        var metadata = new Metadata();
        new ExifReader().extract(new com.drew.lang.ByteArrayReader(exif), metadata, 6);
        var directory = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
        Integer value =
                directory == null ? null : directory.getInteger(ExifIFD0Directory.TAG_ORIENTATION);
        return value != null && value >= 1 && value <= 8 ? value : 1;
    }

    private BufferedImage orient(BufferedImage source, int orientation) {
        int w = source.getWidth(), h = source.getHeight();
        boolean swap = orientation >= 5;
        var target =
                new BufferedImage(
                        swap ? h : w,
                        swap ? w : h,
                        hasTransparency(source)
                                ? BufferedImage.TYPE_INT_ARGB
                                : BufferedImage.TYPE_INT_RGB);
        // getRGB converts the source color space to default sRGB, including CMYK JPEG readers.
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int tx = x, ty = y;
                switch (orientation) {
                    case 2 -> tx = w - 1 - x;
                    case 3 -> {
                        tx = w - 1 - x;
                        ty = h - 1 - y;
                    }
                    case 4 -> ty = h - 1 - y;
                    case 5 -> {
                        tx = y;
                        ty = x;
                    }
                    case 6 -> {
                        tx = h - 1 - y;
                        ty = x;
                    }
                    case 7 -> {
                        tx = h - 1 - y;
                        ty = w - 1 - x;
                    }
                    case 8 -> {
                        tx = y;
                        ty = w - 1 - x;
                    }
                    default -> {}
                }
                target.setRGB(tx, ty, source.getRGB(x, y));
            }
        return target;
    }

    private boolean hasTransparency(BufferedImage image) {
        if (!image.getColorModel().hasAlpha()) return false;
        for (int y = 0; y < image.getHeight(); y++)
            for (int x = 0; x < image.getWidth(); x++)
                if ((image.getRGB(x, y) >>> 24) != 255) return true;
        return false;
    }

    private Rendition encode(BufferedImage pixels, int side, int maxBytes) throws IOException {
        boolean alpha = pixels.getColorModel().hasAlpha();
        String format = alpha ? "png" : "jpeg";
        for (int attempt = 0; attempt < 5; attempt++, side = Math.max(1, side * 3 / 4)) {
            double scale =
                    Math.min(1d, (double) side / Math.max(pixels.getWidth(), pixels.getHeight()));
            int w = Math.max(1, (int) Math.round(pixels.getWidth() * scale)),
                    h = Math.max(1, (int) Math.round(pixels.getHeight() * scale));
            BufferedImage resized = scale == 1 ? pixels : new ResampleOp(w, h).filter(pixels, null);
            var bytes = new ByteArrayOutputStream();
            var writer = ImageIO.getImageWritersByFormatName(format).next();
            try (var output = new MemoryCacheImageOutputStream(bytes)) {
                writer.setOutput(output);
                var params = writer.getDefaultWriteParam();
                if (!alpha) {
                    params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                    params.setCompressionQuality(.85f);
                }
                writer.write(null, new IIOImage(resized, null, null), params);
                output.flush();
            } finally {
                writer.dispose();
            }
            if (bytes.size() <= maxBytes) {
                byte[] value = bytes.toByteArray();
                return new Rendition(
                        value, "image/" + format, w, h, FilesystemMediaStorage.sha(value));
            }
        }
        throw error(422, "MEDIA_OUTPUT_TOO_LARGE");
    }

    private static ApiException error(int status, String code) {
        return new ApiException(
                status,
                code,
                "Cette image ne peut pas être traitée. Vérifiez son format et sa taille.");
    }
}
