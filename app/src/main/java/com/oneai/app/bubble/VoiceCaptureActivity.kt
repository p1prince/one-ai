package com.oneai.app.bubble

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.content.pm.PackageManager

/** External-to-Bubble, one-shot Android speech recognition and microphone permission flow. */
class VoiceCaptureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            launchRecognizer()
        } else {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQUEST_AUDIO)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_AUDIO) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) launchRecognizer()
        else finishWithError("Microphone permission was not granted.")
    }

    private fun launchRecognizer() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your command to ONE AI")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_RECOGNITION)
        } catch (_: Exception) {
            finishWithError("No compatible Android speech recognition service is available.")
        }
    }

    @Deprecated("Uses the Android speech-recognizer activity result contract")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_RECOGNITION) return
        if (resultCode != RESULT_OK) {
            finishWithError("Voice input was cancelled.")
            return
        }
        val transcript = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()?.trim().orEmpty()
        if (transcript.length !in 2..4_000) finishWithError("No usable speech was recognized. Please try again.")
        else finishWithTranscript(transcript)
    }

    private fun finishWithTranscript(transcript: String) {
        deliverResult(true, transcript)
    }

    private fun finishWithError(message: String) {
        deliverResult(false, message)
    }

    private fun deliverResult(success: Boolean, value: String) {
        runCatching {
            startService(Intent(this, FloatingBubbleService::class.java).apply {
                action = FloatingBubbleService.ACTION_VOICE_CAPTURE_RESULT
                putExtra(FloatingBubbleService.EXTRA_VOICE_SUCCESS, success)
                putExtra(FloatingBubbleService.EXTRA_VOICE_TEXT, value)
            })
        }
        finish()
    }

    companion object {
        private const val REQUEST_AUDIO = 8201
        private const val REQUEST_RECOGNITION = 8202
    }
}
