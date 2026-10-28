package com.biecuoguo.media.internal;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Set;

/**
 * E04：信息流和详情页用的缩小图。
 *
 * <p>尺寸按「短边不超过 size」算，保持原比例，不放大。客户端按格子的长边乘屏幕倍数挑最近的一档，
 * 铺满（aspect fill）也不会糊。只处理 JPEG / PNG：GIF 缩了会丢动画，WebP 是 JDK 解不了，二者直接用原图。
 *
 * <p>解码前先读宽高：像素太多的直接放弃（防解压炸弹），大图用隔行采样解码，内存只按目标尺寸的两倍算。
 * 输出一律 JPEG，透明区域铺白底；JPEG 原图的 EXIF 方向会先转正，因为缩小图不再带 EXIF。
 */
final class ImageVariants {

    /** 允许的档位。只认这几个，免得任意尺寸被拿来刷存储和 CPU。 */
    static final Set<Integer> SIZES = Set.of(360, 720, 1080);

    /** 原图像素上限，超过的不做缩小图。 */
    static final long MAX_SOURCE_PIXELS = 50_000_000L;

    /** 解码后的像素上限：采样后仍超过就继续加大采样步长。 */
    static final long MAX_DECODED_PIXELS = 12_000_000L;

    private static final float JPEG_QUALITY = 0.82f;

    private ImageVariants() {
    }

    static boolean supports(String contentType) {
        return "image/jpeg".equals(contentType) || "image/png".equals(contentType);
    }

    /**
     * @return 缩好的 JPEG；原图短边本来就不超过 size、认不出、像素太多时返回 {@code null}（调用方用原图）
     */
    static byte[] shrink(byte[] source, int size) throws IOException {
        if (!SIZES.contains(size)) {
            throw new IllegalArgumentException("unsupported size " + size);
        }
        int orientation = ExifOrientation.read(source);
        BufferedImage decoded;
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(source))) {
            if (input == null) return null;
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) return null;
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > MAX_SOURCE_PIXELS) return null;
                if (Math.min(width, height) <= size) return null;
                ImageReadParam param = reader.getDefaultReadParam();
                int step = subsampling(width, height, size);
                if (step > 1) param.setSourceSubsampling(step, step, 0, 0);
                decoded = reader.read(0, param);
            } finally {
                reader.dispose();
            }
        }
        if (decoded == null) return null;
        double scale = (double) size / Math.min(decoded.getWidth(), decoded.getHeight());
        int targetWidth = Math.max(1, (int) Math.round(decoded.getWidth() * scale));
        int targetHeight = Math.max(1, (int) Math.round(decoded.getHeight() * scale));
        BufferedImage scaled = orient(scale(decoded, targetWidth, targetHeight), orientation);
        return encodeJpeg(scaled);
    }

    /** 采样步长：解码结果的短边至少是目标的两倍（留给平滑缩小），像素总数不超过上限。 */
    static int subsampling(int width, int height, int size) {
        int step = Math.max(1, Math.min(width, height) / (size * 2));
        while (((long) width / step) * ((long) height / step) > MAX_DECODED_PIXELS) {
            step++;
        }
        return step;
    }

    /** 逐级对半缩，最后一步缩到目标尺寸。一步到位的双线性缩小会有明显锯齿。 */
    private static BufferedImage scale(BufferedImage source, int width, int height) {
        BufferedImage current = flatten(source);
        int w = current.getWidth();
        int h = current.getHeight();
        while (w / 2 >= width && h / 2 >= height) {
            w /= 2;
            h /= 2;
            current = draw(current, w, h);
        }
        return (w == width && h == height) ? current : draw(current, width, height);
    }

    /** 统一成不带透明通道的 RGB，透明处铺白；JPEG 编码器不认带 alpha 的图。 */
    private static BufferedImage flatten(BufferedImage source) {
        if (source.getType() == BufferedImage.TYPE_INT_RGB) return source;
        BufferedImage rgb = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
            g.drawImage(source, 0, 0, null);
        } finally {
            g.dispose();
        }
        return rgb;
    }

    private static BufferedImage draw(BufferedImage source, int width, int height) {
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = target.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(source, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        return target;
    }

    /** 按 EXIF 方向（1–8）把像素转正。 */
    static BufferedImage orient(BufferedImage image, int orientation) {
        if (orientation < 2 || orientation > 8) return image;
        int w = image.getWidth();
        int h = image.getHeight();
        boolean swap = orientation >= 5;
        AffineTransform t = new AffineTransform();
        switch (orientation) {
            case 2 -> { t.translate(w, 0); t.scale(-1, 1); }
            case 3 -> { t.translate(w, h); t.rotate(Math.PI); }
            case 4 -> { t.translate(0, h); t.scale(1, -1); }
            case 5 -> { t.rotate(Math.PI / 2); t.scale(1, -1); }
            case 6 -> { t.translate(h, 0); t.rotate(Math.PI / 2); }
            case 7 -> { t.scale(-1, 1); t.translate(-h, 0); t.translate(0, w); t.rotate(-Math.PI / 2); }
            case 8 -> { t.translate(0, w); t.rotate(-Math.PI / 2); }
            default -> { }
        }
        BufferedImage target = new BufferedImage(swap ? h : w, swap ? w : h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = target.createGraphics();
        try {
            g.drawImage(image, t, null);
        } finally {
            g.dispose();
        }
        return target;
    }

    private static byte[] encodeJpeg(BufferedImage image) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(output);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }
}
