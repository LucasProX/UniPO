package com.biecuoguo.media.internal;

import java.io.IOException;
import java.io.InputStream;

/**
 * 按文件内容（头几个字节）判断图片类型。
 *
 * <p>为什么不信 Content-Type、也不信扩展名：这两个都完全由客户端说了算。
 * 原来的校验只看请求头是不是以 {@code image/} 开头，于是一个 HTML 文件、
 * 一个带 {@code <script>} 的 SVG，只要请求头写 {@code image/png} 就能通过，
 * 然后被原样存进 MinIO，再被 {@code /api/media/{id}/content} 用同一个 Content-Type 吐给浏览器。
 *
 * <p>所以白名单只认真实图片的文件头，认不出来的一律拒绝。
 * 故意不支持 SVG：它是 XML，能内联脚本，而「上传一张图片」这个场景里没人需要它。
 */
final class ImageSignature {

    /** 判断类型只需要头 12 个字节（WebP 的 WEBP 标记在第 8-11 位）。 */
    static final int HEAD_BYTES = 12;

    /** 认出来的格式。扩展名和 Content-Type 都由这里决定，不再采信客户端。 */
    record Kind(String extension, String contentType) {
    }

    private ImageSignature() {
    }

    /**
     * 从流里读头几个字节判断类型；流不用回退，读多少算多少。
     *
     * @return 认出来的格式；认不出来返回 {@code null}（由调用方报错）
     */
    static Kind detect(InputStream in) throws IOException {
        return detect(in.readNBytes(HEAD_BYTES));
    }

    static Kind detect(byte[] head) {
        if (head == null || head.length < 4) {
            return null;
        }
        // JPEG: FF D8 FF
        if (matchesAt(head, 0, 0xFF, 0xD8, 0xFF)) {
            return new Kind("jpg", "image/jpeg");
        }
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if (matchesAt(head, 0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
            return new Kind("png", "image/png");
        }
        // GIF: "GIF87a" / "GIF89a"
        if (matchesAt(head, 0, 'G', 'I', 'F', '8')) {
            return new Kind("gif", "image/gif");
        }
        // WebP: "RIFF" + 4 字节长度 + "WEBP"
        if (matchesAt(head, 0, 'R', 'I', 'F', 'F') && matchesAt(head, 8, 'W', 'E', 'B', 'P')) {
            return new Kind("webp", "image/webp");
        }
        return null;
    }

    private static boolean matchesAt(byte[] data, int offset, int... expected) {
        if (data.length < offset + expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if ((data[offset + i] & 0xFF) != (expected[i] & 0xFF)) {
                return false;
            }
        }
        return true;
    }
}
