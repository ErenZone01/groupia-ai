package sn.groupia;

import static org.assertj.core.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.*;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import sn.groupia.media.infrastructure.MediaProperties;
import sn.groupia.media.infrastructure.image.ImageNormalizer;
import sn.groupia.shared.api.ApiException;

class MediaImageTests {
    static MediaProperties properties(Map<String, Object> overrides) {
        Map<String, Object> values = new HashMap<>(overrides);
        values.putIfAbsent("groupia.media.enabled", false);
        return new Binder(new MapConfigurationPropertySource(values))
                .bind("groupia.media", Bindable.of(MediaProperties.class))
                .get();
    }

    private ImageNormalizer normalizer() {
        return new ImageNormalizer(properties(Map.of()));
    }

    static byte[] image(String format, int width, int height, boolean alpha) throws Exception {
        var image =
                new BufferedImage(
                        width,
                        height,
                        alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, alpha ? 0x40ff0000 : 0xffff0000);
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(image, format, bytes);
        return bytes.toByteArray();
    }

    @Test
    void requiredReadersAreDiscovered() {
        ImageNormalizer.requireReaders();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "UklGRhwAAABXRUJQVlA4TA8AAAAvAkAAEAcQ/Y8CBiKi/wEA",
                "UklGRhwAAABXRUJQVlA4TA8AAAAvAkAAAAcQ/Y/+ByKi/wEA"
            })
    void acceptsRealWebpAndPreservesTransparency(String fixture) throws Exception {
        var result =
                normalizer()
                        .normalize(
                                new ByteArrayInputStream(Base64.getDecoder().decode(fixture)),
                                "image/webp");
        boolean alpha = fixture.contains("EAcQ");
        assertThat(result.display().contentType()).isEqualTo(alpha ? "image/png" : "image/jpeg");
        assertThat(result.display().width()).isEqualTo(3);
        if (alpha)
            assertThat(
                            ImageIO.read(new ByteArrayInputStream(result.display().bytes()))
                                            .getRGB(0, 0)
                                    >>> 24)
                    .isEqualTo(128);
    }

    @ParameterizedTest
    @ValueSource(strings = {"jpeg", "png"})
    void normalizesOpaqueImagesWithoutUpscale(String format) throws Exception {
        var result =
                normalizer()
                        .normalize(
                                new ByteArrayInputStream(image(format, 20, 10, false)),
                                "image/" + format);
        assertThat(result.display().width()).isEqualTo(20);
        assertThat(result.display().height()).isEqualTo(10);
        assertThat(result.display().contentType()).isEqualTo("image/jpeg");
        assertThat(result.thumbnail().width()).isEqualTo(20);
    }

    @Test
    void transparentPngKeepsAlpha() throws Exception {
        var result =
                normalizer()
                        .normalize(new ByteArrayInputStream(image("png", 3, 2, true)), "image/png");
        assertThat(result.display().contentType()).isEqualTo("image/png");
        var decoded = ImageIO.read(new ByteArrayInputStream(result.display().bytes()));
        assertThat(decoded.getRGB(0, 0) >>> 24).isEqualTo(0x40);
    }

    @Test
    void resizesBothRenditions() throws Exception {
        var result =
                normalizer()
                        .normalize(
                                new ByteArrayInputStream(image("jpeg", 2400, 1200, false)),
                                "image/jpeg");
        assertThat(result.display().width()).isEqualTo(2000);
        assertThat(result.thumbnail().width()).isEqualTo(480);
        assertThat(result.display().height()).isEqualTo(1000);
        assertThat(result.thumbnail().height()).isEqualTo(240);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "<html>not an image</html>",
                "<svg xmlns='http://www.w3.org/2000/svg'/>",
                "GIF89a",
                "%PDF"
            })
    void rejectsRenamedUnsupportedFiles(String content) {
        assertThatThrownBy(
                        () ->
                                normalizer()
                                        .normalize(
                                                new ByteArrayInputStream(content.getBytes()),
                                                "image/jpeg"))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.status()).isEqualTo(415));
    }

    @Test
    void rejectsMimeMismatch() throws Exception {
        byte[] png = image("png", 2, 2, false);
        assertThatThrownBy(
                        () -> normalizer().normalize(new ByteArrayInputStream(png), "image/jpeg"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.code()).isEqualTo("MEDIA_CONTENT_TYPE_MISMATCH"));
    }

    @Test
    void rejectsTooManyBytes() {
        assertThatThrownBy(
                        () ->
                                normalizer()
                                        .normalize(
                                                new ByteArrayInputStream(new byte[8388609]),
                                                "image/png"))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.status()).isEqualTo(413));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void boundsDimensionsBeforeDecoding(int scenario) throws Exception {
        byte[] png = image("png", 1, 1, false);
        ByteBuffer.wrap(png, 16, 8)
                .putInt(scenario == 2 ? 1 : 6001)
                .putInt(scenario == 1 ? 1 : 6001);
        var crc = new java.util.zip.CRC32();
        crc.update(png, 12, 17);
        ByteBuffer.wrap(png, 29, 4).putInt((int) crc.getValue());
        assertThatThrownBy(() -> normalizer().normalize(new ByteArrayInputStream(png), "image/png"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.code()).isEqualTo("MEDIA_DIMENSIONS_EXCEEDED"));
    }

    @Test
    void boundsPixelsSeparately() throws Exception {
        byte[] png = image("png", 1, 1, false);
        ByteBuffer.wrap(png, 16, 8).putInt(5000).putInt(5000);
        var crc = new java.util.zip.CRC32();
        crc.update(png, 12, 17);
        ByteBuffer.wrap(png, 29, 4).putInt((int) crc.getValue());
        assertThatThrownBy(() -> normalizer().normalize(new ByteArrayInputStream(png), "image/png"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.code()).isEqualTo("MEDIA_DIMENSIONS_EXCEEDED"));
    }

    @Test
    void rejectsCorruptJpeg() {
        assertThatThrownBy(
                        () ->
                                normalizer()
                                        .normalize(
                                                new ByteArrayInputStream(
                                                        new byte[] {-1, -40, 0, 0, -1, -39}),
                                                "image/jpeg"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void rejectsAnimatedPngAndWebpContainers() throws Exception {
        byte[] png = image("png", 1, 1, false);
        var animated = new ByteArrayOutputStream();
        animated.write(png, 0, 33);
        var chunk = ByteBuffer.allocate(20);
        chunk.putInt(8).put("acTL".getBytes()).putInt(2).putInt(0);
        var crc = new java.util.zip.CRC32();
        crc.update(chunk.array(), 4, 12);
        chunk.putInt((int) crc.getValue());
        animated.write(chunk.array());
        animated.write(png, 33, png.length - 33);
        assertThatThrownBy(
                        () ->
                                normalizer()
                                        .normalize(
                                                new ByteArrayInputStream(animated.toByteArray()),
                                                "image/png"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.code()).isEqualTo("MEDIA_ANIMATION_UNSUPPORTED"));
        var webp = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN);
        webp.put("RIFF".getBytes()).putInt(22).put("WEBPVP8X".getBytes()).putInt(10).put((byte) 2);
        assertThatThrownBy(
                        () ->
                                normalizer()
                                        .normalize(
                                                new ByteArrayInputStream(webp.array()),
                                                "image/webp"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.code()).isEqualTo("MEDIA_ANIMATION_UNSUPPORTED"));
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3, 4, 5, 6, 7, 8})
    void appliesExifMirrorsAndRotationsAndStripsMetadata(int orientation) throws Exception {
        var source =
                new java.awt.image.BufferedImage(64, 32, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var graphics = source.createGraphics();
        graphics.setColor(java.awt.Color.WHITE);
        graphics.fillRect(0, 0, 64, 32);
        graphics.setColor(java.awt.Color.RED);
        graphics.fillRect(0, 0, 32, 16);
        graphics.dispose();
        var encoded = new ByteArrayOutputStream();
        ImageIO.write(source, "jpeg", encoded);
        byte[] jpeg = encoded.toByteArray();
        var exif = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
        exif.put("Exif\0\0".getBytes())
                .put((byte) 'I')
                .put((byte) 'I')
                .putShort((short) 42)
                .putInt(8)
                .putShort((short) 1)
                .putShort((short) 0x112)
                .putShort((short) 3)
                .putInt(1)
                .putShort((short) orientation)
                .putShort((short) 0)
                .putInt(0);
        var input = new ByteArrayOutputStream();
        input.write(jpeg, 0, 2);
        input.write(new byte[] {-1, -31, 0, 34});
        input.write(exif.array());
        input.write(jpeg, 2, jpeg.length - 2);
        var result =
                normalizer().normalize(new ByteArrayInputStream(input.toByteArray()), "image/jpeg");
        assertThat(result.display().width()).isEqualTo(orientation >= 5 ? 32 : 64);
        assertThat(result.display().height()).isEqualTo(orientation >= 5 ? 64 : 32);
        int[][] redPixel = {{51, 8}, {51, 23}, {12, 23}, {8, 12}, {23, 12}, {23, 51}, {8, 51}};
        var output = ImageIO.read(new ByteArrayInputStream(result.display().bytes()));
        var pixel =
                new java.awt.Color(
                        output.getRGB(redPixel[orientation - 2][0], redPixel[orientation - 2][1]));
        assertThat(pixel.getRed()).isGreaterThan(200);
        assertThat(pixel.getGreen()).isLessThan(40);
        assertThat(
                        new String(
                                result.display().bytes(),
                                java.nio.charset.StandardCharsets.ISO_8859_1))
                .doesNotContain("Exif", "GPS", "xmp");
    }
}
