package com.family.finance.service.holdingimport;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;

class HoldingImportImageTest {
    @Test void actualSignaturesDetermineTheStoredImageType() {
        assertThat(HoldingImportService.imageMime(new byte[]{(byte)0x89,'P','N','G',13,10,26,10})).isEqualTo("image/png");
        assertThat(HoldingImportService.imageMime(new byte[]{(byte)0xff,(byte)0xd8,(byte)0xff,0})).isEqualTo("image/jpeg");
        assertThat(HoldingImportService.imageMime(new byte[]{'R','I','F','F',0,0,0,0,'W','E','B','P'})).isEqualTo("image/webp");
    }
    @Test void arbitraryContentCannotBecomeAnImageThroughADeclaredMimeType() {
        for(byte[] bytes:new byte[][]{new byte[0],new byte[]{(byte)0x89,'P','N','G'},"<html>synthetic</html>".getBytes(StandardCharsets.UTF_8),"RIFFfakeWAVE".getBytes(StandardCharsets.US_ASCII)})
            assertThatThrownBy(()->HoldingImportService.imageMime(bytes)).isInstanceOf(IllegalArgumentException.class);
    }
}
