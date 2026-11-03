package com.oneai.app.plugins

/** Bounds transcripts and speech output before they reach the AI or Android TTS APIs. */
object VoiceTextPolicy {
    fun normalizeTranscript(value: String): String? {
        val clean = value.trim()
        if (clean.length !in 2..4_000 || clean.any { it.isISOControl() }) {
            return null
        }
        return clean
    }

    fun speechText(value: String): String? =
        value.trim().takeIf { it.isNotEmpty() }?.take(MAX_SPEECH_CHARS)

    private const val MAX_SPEECH_CHARS = 3_500
}
