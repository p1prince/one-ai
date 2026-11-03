package com.oneai.app.ai

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Reads provider response bodies with a strict byte ceiling to bound memory use. */
object AiResponseBodyReader {
    fun readUtf8(input: InputStream, maxBytes: Int): String? {
        require(maxBytes > 0) { "Response size limit must be positive." }
        val output = ByteArrayOutputStream(minOf(maxBytes, 8 * 1024))
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            if (count > maxBytes - total) return null
            output.write(buffer, 0, count)
            total += count
        }
        return output.toString(Charsets.UTF_8.name())
    }
}
