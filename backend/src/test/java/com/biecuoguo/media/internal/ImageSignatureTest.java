package com.biecuoguo.media.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 上传只认文件头。这几条断言就是「把 HTML/SVG 改名成 .png 也传不上去」的保证。
 */
class ImageSignatureTest {
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] GIF = {'G', 'I', 'F', '8', '9', 'a'};
    private static final byte[] WEBP = {'R', 'I', 'F', 'F', 0x24, 0, 0, 0, 'W', 'E', 'B', 'P'};

    @Test
    void recognisesSupportedFormats() {
        assertEquals("jpg", ImageSignature.detect(JPEG).extension());
        assertEquals("image/png", ImageSignature.detect(PNG).contentType());
        assertEquals("gif", ImageSignature.detect(GIF).extension());
        assertEquals("webp", ImageSignature.detect(WEBP).extension());
        assertEquals("image/webp", ImageSignature.detect(WEBP).contentType());
    }

    @Test
    void rejectsHtmlAndSvg() {
        assertNull(ImageSignature.detect("<html><script>alert(1)</script>".getBytes()));
        assertNull(ImageSignature.detect("<svg xmlns=\"http://www.w3.org/2000/svg\">".getBytes()));
    }

    @Test
    void rejectsTooShortOrNull() {
        assertNull(ImageSignature.detect(new byte[]{1, 2}));
        assertNull(ImageSignature.detect(new byte[0]));
        assertNull(ImageSignature.detect((byte[]) null));
    }

    @Test
    void riffWithoutWebpMarkerIsNotAnImage() {
        byte[] riffWav = {'R', 'I', 'F', 'F', 0x24, 0, 0, 0, 'W', 'A', 'V', 'E'};
        assertNull(ImageSignature.detect(riffWav));
    }
}
