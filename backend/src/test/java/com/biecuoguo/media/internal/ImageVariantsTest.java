package com.biecuoguo.media.internal;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImageVariantsTest {

    @Test
    void shrinksTheShortSideToTheRequestedSize() throws IOException {
        byte[] shrunk = ImageVariants.shrink(encode(halves(2000, 1000, BufferedImage.TYPE_INT_RGB), "jpg"), 360);
        BufferedImage out = decode(shrunk);
        assertThat(out.getWidth()).isEqualTo(720);
        assertThat(out.getHeight()).isEqualTo(360);
        assertThat(ImageSignature.detect(shrunk).contentType()).isEqualTo("image/jpeg");
    }

    @Test
    void transparentPngIsFlattenedOnWhite() throws IOException {
        BufferedImage png = new BufferedImage(800, 600, BufferedImage.TYPE_INT_ARGB);
        BufferedImage out = decode(ImageVariants.shrink(encode(png, "png"), 360));
        assertThat(out.getWidth()).isEqualTo(480);
        Color corner = new Color(out.getRGB(2, 2));
        assertThat(corner.getRed()).isGreaterThan(245);
        assertThat(corner.getBlue()).isGreaterThan(245);
    }

    @Test
    void neverUpscalesAndSkipsWhatItCannotRead() throws IOException {
        assertThat(ImageVariants.shrink(encode(halves(300, 300, BufferedImage.TYPE_INT_RGB), "jpg"), 360)).isNull();
        assertThat(ImageVariants.shrink(encode(halves(1080, 2000, BufferedImage.TYPE_INT_RGB), "jpg"), 1080)).isNull();
        assertThat(ImageVariants.shrink("not an image".getBytes(), 720)).isNull();
        assertThatThrownBy(() -> ImageVariants.shrink(new byte[0], 500)).isInstanceOf(IllegalArgumentException.class);
        assertThat(ImageVariants.supports("image/gif")).isFalse();
        assertThat(ImageVariants.supports("image/webp")).isFalse();
    }

    @Test
    void largeSourcesAreSubsampledBeforeDecoding() {
        assertThat(ImageVariants.subsampling(2048, 1536, 1080)).isEqualTo(1);
        assertThat(ImageVariants.subsampling(8000, 6000, 360)).isEqualTo(8);
        int step = ImageVariants.subsampling(7000, 7000, 1080);
        assertThat((7000L / step) * (7000L / step)).isLessThanOrEqualTo(ImageVariants.MAX_DECODED_PIXELS);
    }

    @Test
    void portraitPhotosAreTurnedUprightFromExif() throws IOException {
        // 手机竖拍：像素横着存（左红右蓝），EXIF 方向 6 = 顺时针转 90°
        byte[] jpeg = withOrientation(encode(halves(1600, 800, BufferedImage.TYPE_INT_RGB), "jpg"), 6, true);
        assertThat(ExifOrientation.read(jpeg)).isEqualTo(6);
        BufferedImage out = decode(ImageVariants.shrink(jpeg, 360));
        assertThat(out.getWidth()).isEqualTo(360);
        assertThat(out.getHeight()).isEqualTo(720);
        assertThat(new Color(out.getRGB(180, 40)).getRed()).isGreaterThan(200);   // 原来的左半边转到了上面
        assertThat(new Color(out.getRGB(180, 680)).getBlue()).isGreaterThan(200);
    }

    @Test
    void everyOrientationLandsTheTopLeftCornerCorrectly() {
        // 原图左上角一个红点；转正后红点应该落在的角
        int[][] expected = {{}, {0, 0}, {1, 0}, {1, 1}, {0, 1}, {0, 0}, {1, 0}, {1, 1}, {0, 1}};
        for (int orientation = 1; orientation <= 8; orientation++) {
            BufferedImage src = new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB);
            src.setRGB(0, 0, Color.RED.getRGB());
            BufferedImage out = ImageVariants.orient(src, orientation);
            int x = expected[orientation][0] == 0 ? 0 : out.getWidth() - 1;
            int y = expected[orientation][1] == 0 ? 0 : out.getHeight() - 1;
            assertThat(out.getRGB(x, y)).as("orientation %d", orientation).isEqualTo(Color.RED.getRGB());
            assertThat(out.getWidth()).isEqualTo(orientation >= 5 ? 20 : 40);
        }
    }

    @Test
    void exifReaderHandlesBothByteOrdersAndGarbage() throws IOException {
        byte[] base = encode(halves(100, 100, BufferedImage.TYPE_INT_RGB), "jpg");
        assertThat(ExifOrientation.read(withOrientation(base, 8, false))).isEqualTo(8);
        assertThat(ExifOrientation.read(withOrientation(base, 3, true))).isEqualTo(3);
        assertThat(ExifOrientation.read(base)).isEqualTo(1);
        assertThat(ExifOrientation.read(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE1, 0, 30, 'E', 'x'}))
                .isEqualTo(1);
        assertThat(ExifOrientation.read(null)).isEqualTo(1);
    }

    private static BufferedImage halves(int width, int height, int type) {
        BufferedImage image = new BufferedImage(width, height, type);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, width / 2, height);
        g.setColor(Color.BLUE);
        g.fillRect(width / 2, 0, width - width / 2, height);
        g.dispose();
        return image;
    }

    private static byte[] encode(BufferedImage image, String format) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
        return out.toByteArray();
    }

    private static BufferedImage decode(byte[] bytes) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }

    /** 在 SOI 后面插一段只含方向标记的 EXIF。 */
    private static byte[] withOrientation(byte[] jpeg, int orientation, boolean little) {
        byte[] tiff = little
                ? new byte[]{'I', 'I', 42, 0, 8, 0, 0, 0, 1, 0, 0x12, 0x01, 3, 0, 1, 0, 0, 0, (byte) orientation, 0, 0, 0, 0, 0, 0, 0}
                : new byte[]{'M', 'M', 0, 42, 0, 0, 0, 8, 0, 1, 0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, (byte) orientation, 0, 0, 0, 0, 0, 0};
        int length = 2 + 6 + tiff.length;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);
        out.write(0xFF);
        out.write(0xE1);
        out.write(length >> 8);
        out.write(length & 0xFF);
        out.writeBytes(new byte[]{'E', 'x', 'i', 'f', 0, 0});
        out.writeBytes(tiff);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }
}
