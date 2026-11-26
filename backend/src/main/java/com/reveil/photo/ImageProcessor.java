package com.reveil.photo;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Iterator;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Contrôle du contenu d'une image (type réel d'après la signature, dimensions) et génération de la miniature.
 * Seuls JPEG et PNG sont acceptés : le mobile envoie du JPEG compressé ; ImageIO ne lit ni HEIC ni WebP.
 */
@Component
public class ImageProcessor {

    static final String UNSUPPORTED = "Format non pris en charge : seules les images JPEG et PNG sont acceptées";
    static final String UNREADABLE = "Image illisible ou corrompue";

    private static final float THUMBNAIL_QUALITY = 0.8f;

    private final PhotoSettings settings;

    public ImageProcessor(PhotoSettings settings) {
        this.settings = settings;
    }

    /** Image contrôlée, avec sa miniature JPEG. */
    public record Processed(String contentType, int width, int height, byte[] thumbnail) {
    }

    public Processed process(byte[] data) {
        String contentType = detectContentType(data);
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw new InvalidPhotoException(HttpStatus.BAD_REQUEST, UNREADABLE);
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                // Dimensions lues dans l'en-tête, avant décodage : refuse les images démesurées.
                if (width <= 0 || height <= 0 || (long) width * height > settings.maxPixels()) {
                    throw new InvalidPhotoException(HttpStatus.PAYLOAD_TOO_LARGE, "Image trop grande (dimensions)");
                }
                BufferedImage image = reader.read(0);
                return new Processed(contentType, width, height, thumbnail(image));
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof InvalidPhotoException ipe) {
                throw ipe;
            }
            throw new InvalidPhotoException(HttpStatus.BAD_REQUEST, UNREADABLE);
        }
    }

    /** Type d'après la signature du fichier (le type déclaré par le client n'est pas fiable). */
    static String detectContentType(byte[] data) {
        if (data.length >= 3 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8 && (data[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (data.length >= 8 && (data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G'
                && data[4] == 0x0D && data[5] == 0x0A && data[6] == 0x1A && data[7] == 0x0A) {
            return "image/png";
        }
        throw new InvalidPhotoException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, UNSUPPORTED);
    }

    /** Miniature JPEG dont le plus grand côté vaut {@code thumbnailSize} (jamais agrandie). Transparence → fond blanc. */
    byte[] thumbnail(BufferedImage source) {
        int max = settings.thumbnailSize();
        double scale = Math.min(1.0, (double) max / Math.max(source.getWidth(), source.getHeight()));
        int w = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int h = Math.max(1, (int) Math.round(source.getHeight() * scale));
        BufferedImage target = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = target.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, h);
            g.drawImage(downscale(source, w, h), 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return writeJpeg(target);
    }

    /** Réduction par paliers successifs de moitié : qualité correcte avec l'interpolation bilinéaire. */
    private static BufferedImage downscale(BufferedImage source, int w, int h) {
        BufferedImage current = source;
        int cw = source.getWidth();
        int ch = source.getHeight();
        while (cw / 2 >= w && ch / 2 >= h) {
            cw /= 2;
            ch /= 2;
            BufferedImage step = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = step.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(current, 0, 0, cw, ch, null);
            } finally {
                g.dispose();
            }
            current = step;
        }
        return current;
    }

    private static byte[] writeJpeg(BufferedImage image) {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(THUMBNAIL_QUALITY);
            writer.write(null, new IIOImage(image, null, null), param);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }
}
