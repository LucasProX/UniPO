package com.biecuoguo.media.internal;

/**
 * 从 JPEG 的 EXIF（APP1）里读方向标记 0x0112。
 *
 * <p>ImageIO 解码时不看这个标记：手机竖拍的照片像素是横着存的，靠它告诉看图软件转 90°。
 * 缩小图重新编码后不再带 EXIF，所以得在这里先读出来、自己把像素转正。
 * 只读这一个标记，任何地方不对就当作 1（不转），不抛异常。
 */
final class ExifOrientation {

    private static final int TAG_ORIENTATION = 0x0112;

    private ExifOrientation() {
    }

    /** @return 1–8；不是 JPEG、没有 EXIF 或读不懂时返回 1 */
    static int read(byte[] jpeg) {
        try {
            return find(jpeg);
        } catch (RuntimeException ex) {
            return 1;
        }
    }

    private static int find(byte[] d) {
        if (d == null || d.length < 4 || u8(d, 0) != 0xFF || u8(d, 1) != 0xD8) return 1;
        int pos = 2;
        while (pos + 4 <= d.length) {
            if (u8(d, pos) != 0xFF) return 1;
            int marker = u8(d, pos + 1);
            if (marker == 0xFF) { pos++; continue; }
            // SOS / EOI 之后是图像数据，EXIF 只会在它们前面。
            if (marker == 0xDA || marker == 0xD9) return 1;
            int length = (u8(d, pos + 2) << 8) | u8(d, pos + 3);
            if (length < 2) return 1;
            int segment = pos + 4;
            if (marker == 0xE1 && length >= 16 && segment + 6 <= d.length
                    && d[segment] == 'E' && d[segment + 1] == 'x' && d[segment + 2] == 'i' && d[segment + 3] == 'f'
                    && d[segment + 4] == 0 && d[segment + 5] == 0) {
                return fromTiff(d, segment + 6, Math.min(d.length, pos + 2 + length));
            }
            pos += 2 + length;
        }
        return 1;
    }

    private static int fromTiff(byte[] d, int tiff, int end) {
        if (tiff + 8 > end) return 1;
        boolean little;
        if (d[tiff] == 'I' && d[tiff + 1] == 'I') little = true;
        else if (d[tiff] == 'M' && d[tiff + 1] == 'M') little = false;
        else return 1;
        if (u16(d, tiff + 2, little) != 42) return 1;
        long ifd = u32(d, tiff + 4, little);
        int ifdStart = (int) (tiff + ifd);
        if (ifd < 8 || ifdStart + 2 > end) return 1;
        int count = u16(d, ifdStart, little);
        for (int i = 0; i < count; i++) {
            int entry = ifdStart + 2 + i * 12;
            if (entry + 12 > end) return 1;
            if (u16(d, entry, little) == TAG_ORIENTATION) {
                int value = u16(d, entry + 8, little);
                return value >= 1 && value <= 8 ? value : 1;
            }
        }
        return 1;
    }

    private static int u8(byte[] d, int i) {
        return d[i] & 0xFF;
    }

    private static int u16(byte[] d, int i, boolean little) {
        return little ? u8(d, i) | (u8(d, i + 1) << 8) : (u8(d, i) << 8) | u8(d, i + 1);
    }

    private static long u32(byte[] d, int i, boolean little) {
        return little
                ? (u16(d, i, true) & 0xFFFFL) | ((u16(d, i + 2, true) & 0xFFFFL) << 16)
                : ((u16(d, i, false) & 0xFFFFL) << 16) | (u16(d, i + 2, false) & 0xFFFFL);
    }
}
