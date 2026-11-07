package com.oneai.app.ai

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiResponseBodyReaderTest {
    @Test fun readsUtf8WithinLimit() {
        assertEquals("ONE AI response", AiResponseBodyReader.readUtf8(
            ByteArrayInputStream("ONE AI response".toByteArray()), 32
        ))
    }

    @Test fun rejectsResponseLargerThanLimit() {
        assertNull(AiResponseBodyReader.readUtf8(
            ByteArrayInputStream(ByteArray(33) { 65 }), 32
        ))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonPositiveLimit() {
        AiResponseBodyReader.readUtf8(ByteArrayInputStream(byteArrayOf()), 0)
    }
}
