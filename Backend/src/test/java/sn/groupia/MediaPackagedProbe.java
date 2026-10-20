package sn.groupia;

/** Invoked through the packaged Boot PropertiesLauncher; never a production endpoint. */
public final class MediaPackagedProbe {
    public static void main(String[] args) {
        sn.groupia.media.infrastructure.image.ImageNormalizer.requireReaders();
        System.out.println("PASS: packaged ImageIO readers JPEG PNG WebP");
    }
}
