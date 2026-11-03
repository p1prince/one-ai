package com.oneai.app.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenIntelligenceImagePolicyTest {
    @Test fun recognizesPngSignature() {
        val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        assertEquals("image/png", ScreenIntelligenceImagePolicy.mimeType(bytes))
    }

    @Test fun recognizesJpegSignature() {
        val bytes = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())
        assertEquals("image/jpeg", ScreenIntelligenceImagePolicy.mimeType(bytes))
    }

    @Test fun recognizesWebpRiffSignature() {
        val bytes = byteArrayOf(
            0x52, 0x49, 0x46, 0x46, 0x00, 0x00, 0x00, 0x00,
            0x57, 0x45, 0x42, 0x50
        )
        assertEquals("image/webp", ScreenIntelligenceImagePolicy.mimeType(bytes))
    }

    @Test fun rejectsEmptyTruncatedAndUnknownImageData() {
        assertNull(ScreenIntelligenceImagePolicy.mimeType(byteArrayOf()))
        assertNull(ScreenIntelligenceImagePolicy.mimeType(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)))
        assertNull(ScreenIntelligenceImagePolicy.mimeType("not an image".toByteArray()))
        assertNull(ScreenIntelligenceImagePolicy.mimeType("RIFF1234WEB".toByteArray()))
    }
}
