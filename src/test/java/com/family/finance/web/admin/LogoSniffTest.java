package com.family.finance.web.admin;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.28.3 · issue #25 · Logo 按文件头认格式,不信浏览器报的 Content-Type。
 *
 * <p>Safari 与 iPhone / iPad 上的浏览器不会把 Canvas 编码成 WebP,会悄悄给 PNG ——
 * 原来只收 WebP,这些浏览器上传永远 400。</p>
 */
class LogoSniffTest {

    private static byte[] b(int... v) {
        byte[] out = new byte[v.length];
        for (int i = 0; i < v.length; i++) out[i] = (byte) v[i];
        return out;
    }

    @Test
    void webp_要有_RIFF_和_WEBP_两段() {
        assertThat(LogoUploadController.sniffExtension(b('R', 'I', 'F', 'F', 1, 2, 3, 4, 'W', 'E', 'B', 'P'))).isEqualTo("webp");
        // 只有 RIFF 不算(WAV / AVI 也是 RIFF)
        assertThat(LogoUploadController.sniffExtension(b('R', 'I', 'F', 'F', 1, 2, 3, 4, 'W', 'A', 'V', 'E'))).isNull();
    }

    @Test
    void png_与_jpeg_也收() {
        assertThat(LogoUploadController.sniffExtension(b(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0))).isEqualTo("png");
        assertThat(LogoUploadController.sniffExtension(b(0xFF, 0xD8, 0xFF, 0xE0, 0, 0, 0, 0, 0, 0, 0, 0))).isEqualTo("jpg");
    }

    @Test
    void 认不出的一律不收() {
        assertThat(LogoUploadController.sniffExtension(b('G', 'I', 'F', '8', '9', 'a'))).isNull();
        assertThat(LogoUploadController.sniffExtension(b('<', 's', 'v', 'g'))).isNull();
        assertThat(LogoUploadController.sniffExtension(new byte[0])).isNull();
        assertThat(LogoUploadController.sniffExtension(null)).isNull();
    }
}
