package com.oneai.app.plugins

/** Strict, side-effect-free checks for image bytes passed to the optional vision adapter. */
object ScreenIntelligenceImagePolicy {
    fun mimeType(bytes: ByteArray): String? {
        if (bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4e.toByte() && bytes[3] == 0x47.toByte() &&
            bytes[4] == 0x0d.toByte() && bytes[5] == 0x0a.toByte() &&
            bytes[6] == 0x1a.toByte() && bytes[7] == 0x0a.toByte()) return "image/png"
        if (bytes.size >= 3 && bytes[0] == 0xff.toByte() &&
            bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte()) return "image/jpeg"
        return null
    }
}
