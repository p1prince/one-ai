package com.oneai.app.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceTextPolicyTest {
    @Test fun trimsValidTranscript() {
        assertEquals("Turn on the lights", VoiceTextPolicy.normalizeTranscript("  Turn on the lights  "))
    }

    @Test fun rejectsEmptyShortAndOversizedTranscripts() {
        assertNull(VoiceTextPolicy.normalizeTranscript(""))
        assertNull(VoiceTextPolicy.normalizeTranscript("x"))
        assertNull(VoiceTextPolicy.normalizeTranscript("a".repeat(4_001)))
    }

    @Test fun rejectsControlCharacters() {
        assertNull(VoiceTextPolicy.normalizeTranscript("hello\u0000world"))
    }

    @Test fun boundsSpeechText() {
        assertEquals(3_500, VoiceTextPolicy.speechText("a".repeat(4_000))?.length)
        assertNull(VoiceTextPolicy.speechText("   "))
    }
}
