package com.oneai.app.builtin

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.oneai.app.bubble.VoiceCaptureActivity
import com.oneai.app.plugins.VoiceTextPolicy
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * In-process hand-off between the voice provider and [VoiceCaptureActivity]. The activity reports
 * its transcript here directly instead of through the Bubble service.
 */
object VoiceCaptureBridge {
    private var pending: CancellableContinuation<String>? = null
    private val main = Handler(Looper.getMainLooper())

    suspend fun capture(context: Context): String = suspendCancellableCoroutine { continuation ->
        synchronized(this) { pending = continuation }
        continuation.invokeOnCancellation { clearIfCurrent(continuation) }
        main.post {
            if (!continuation.isActive) return@post
            runCatching {
                context.startActivity(Intent(context, VoiceCaptureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { error ->
                clearIfCurrent(continuation)
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
    }

    fun complete(success: Boolean, value: String) {
        val continuation = synchronized(this) { pending.also { pending = null } } ?: return
        if (!continuation.isActive) return
        if (success) {
            val transcript = VoiceTextPolicy.normalizeTranscript(value)
            if (transcript == null) continuation.resumeWithException(IllegalArgumentException("No usable speech was recognized."))
            else continuation.resume(transcript)
        } else {
            continuation.resumeWithException(IllegalStateException(value.ifBlank { "Voice input was cancelled." }))
        }
    }

    fun cancelPending() {
        val continuation = synchronized(this) { pending.also { pending = null } }
        continuation?.cancel()
    }

    private fun clearIfCurrent(continuation: CancellableContinuation<String>) {
        synchronized(this) { if (pending === continuation) pending = null }
    }
}

/** One-shot Android text-to-speech. */
object VoiceSpeaker {
    private val main = Handler(Looper.getMainLooper())

    suspend fun speak(context: Context, text: String) = suspendCancellableCoroutine<Unit> { continuation ->
        main.post {
            if (!continuation.isActive) return@post
            lateinit var engine: TextToSpeech
            engine = TextToSpeech(context) { status ->
                if (!continuation.isActive) {
                    engine.shutdown()
                    return@TextToSpeech
                }
                if (status != TextToSpeech.SUCCESS) {
                    engine.shutdown()
                    continuation.resumeWithException(IllegalStateException("Android text-to-speech is unavailable."))
                    return@TextToSpeech
                }
                val utteranceId = UUID.randomUUID().toString()
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) = Unit
                    override fun onDone(id: String?) {
                        engine.shutdown()
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                    override fun onError(id: String?) {
                        engine.shutdown()
                        if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Android text-to-speech failed."))
                    }
                })
                val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
                if (result == TextToSpeech.ERROR) {
                    engine.shutdown()
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Android text-to-speech could not start."))
                }
            }
            continuation.invokeOnCancellation {
                main.post {
                    runCatching { engine.stop() }
                    runCatching { engine.shutdown() }
                }
            }
        }
    }
}
