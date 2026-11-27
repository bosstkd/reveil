package com.reveil.photo;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Random;
import javax.imageio.ImageIO;

/** Images générées pour les tests. */
public final class TestImages {

    private TestImages() {
    }

    public static byte[] jpeg(int width, int height) {
        return write(gradient(width, height, BufferedImage.TYPE_INT_RGB), "jpeg");
    }

    static byte[] png(int width, int height) {
        return write(gradient(width, height, BufferedImage.TYPE_INT_ARGB), "png");
    }

    /** PNG de bruit aléatoire (incompressible) : ~3 octets par pixel. */
    static byte[] noisePng(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(42);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, random.nextInt(0x1000000));
            }
        }
        return write(image, "png");
    }

    private static BufferedImage gradient(int width, int height, int type) {
        BufferedImage image = new BufferedImage(width, height, type);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, 0xFF000000 | ((x * 255 / width) << 16) | ((y * 255 / height) << 8) | 0x80);
            }
        }
        return image;
    }

    private static byte[] write(BufferedImage image, String format) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, format, out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }
}
