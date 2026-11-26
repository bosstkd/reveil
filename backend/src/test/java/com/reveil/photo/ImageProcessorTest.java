package com.reveil.photo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.util.unit.DataSize;

class ImageProcessorTest {

    private final ImageProcessor processor = new ImageProcessor(new PhotoSettings(DataSize.ofMegabytes(10), 50_000_000, 400));

    private static BufferedImage read(byte[] data) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(data));
    }

    @Test
    void jpegIsAcceptedWithDimensionsAndALandscapeThumbnailOf400Px() throws Exception {
        ImageProcessor.Processed result = processor.process(TestImages.jpeg(2048, 1536));

        assertThat(result.contentType()).isEqualTo("image/jpeg");
        assertThat(result.width()).isEqualTo(2048);
        assertThat(result.height()).isEqualTo(1536);
        BufferedImage thumb = read(result.thumbnail());
        assertThat(thumb.getWidth()).isEqualTo(400);
        assertThat(thumb.getHeight()).isEqualTo(300);
        assertThat(ImageProcessor.detectContentType(result.thumbnail())).isEqualTo("image/jpeg");
    }

    @Test
    void portraitThumbnailIsBoundedByItsHeight() throws Exception {
        BufferedImage thumb = read(processor.process(TestImages.jpeg(1536, 2048)).thumbnail());
        assertThat(thumb.getWidth()).isEqualTo(300);
        assertThat(thumb.getHeight()).isEqualTo(400);
    }

    @Test
    void smallImageIsNotUpscaled() throws Exception {
        BufferedImage thumb = read(processor.process(TestImages.jpeg(200, 100)).thumbnail());
        assertThat(thumb.getWidth()).isEqualTo(200);
        assertThat(thumb.getHeight()).isEqualTo(100);
    }

    @Test
    void transparentPngIsAcceptedAndItsThumbnailIsAnOpaqueJpeg() throws Exception {
        ImageProcessor.Processed result = processor.process(TestImages.png(800, 600));
        assertThat(result.contentType()).isEqualTo("image/png");
        BufferedImage thumb = read(result.thumbnail());
        assertThat(thumb.getColorModel().hasAlpha()).isFalse();
        assertThat(thumb.getWidth()).isEqualTo(400);
    }

    @Test
    void typeIsDetectedFromContentNotFromTheDeclaredName() {
        byte[] gif = "GIF89a....".getBytes(StandardCharsets.US_ASCII);
        assertThatThrownBy(() -> processor.process(gif))
                .isInstanceOfSatisfying(InvalidPhotoException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE));
        byte[] text = "<html>pas une image</html>".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> processor.process(text))
                .isInstanceOfSatisfying(InvalidPhotoException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE));
    }

    @Test
    void truncatedJpegIsRejectedAsUnreadable() {
        byte[] jpeg = TestImages.jpeg(400, 300);
        byte[] truncated = Arrays.copyOf(jpeg, 40);
        assertThatThrownBy(() -> processor.process(truncated))
                .isInstanceOfSatisfying(InvalidPhotoException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getMessage()).isEqualTo(ImageProcessor.UNREADABLE);
                });
    }

    @Test
    void oversizedDimensionsAreRejectedBeforeDecoding() {
        ImageProcessor strict = new ImageProcessor(new PhotoSettings(DataSize.ofMegabytes(10), 100 * 100, 400));
        assertThatThrownBy(() -> strict.process(TestImages.jpeg(101, 100)))
                .isInstanceOfSatisfying(InvalidPhotoException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE));
    }
}
