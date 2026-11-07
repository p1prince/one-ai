package com.oneai.app.plugins

import com.oneai.app.ai.AiOrchestrator
import kotlinx.coroutines.CancellationException

fun interface VoiceInputProvider {
    suspend fun captureSpeech(): String
}

fun interface VoiceOutputProvider {
    suspend fun speak(text: String)
}

/** Trusted voice assistant adapter: one approved recording, shared AI orchestration, spoken reply. */
class VoiceInterfaceAdapter(
    private val inputProvider: VoiceInputProvider,
    private val outputProvider: VoiceOutputProvider,
    private val orchestrator: AiOrchestrator
) : PluginAdapter {
    override val manifest = PluginManifest(
        id = PLUGIN_ID,
        name = "Voice Interface",
        version = "1.0.0",
        description = "A voice input and spoken response integration.",
        capabilities = listOf("microphone", "audio")
    )

    override suspend fun execute(input: PluginActionInput): PluginActionOutput {
        require(input.actionId == VOICE_ACTION && input.capability == "microphone") {
            "Unsupported Voice Interface action."
        }
        val transcript = VoiceTextPolicy.normalizeTranscript(inputProvider.captureSpeech())
            ?: throw IllegalArgumentException("No usable speech was recognized. Please try again.")
        val response = orchestrator.respond(transcript).content.trim()
            .takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("ONE AI returned an empty response.")
        val speech = VoiceTextPolicy.speechText(response)
        val speechNote = if (speech == null) {
            "Spoken playback was unavailable; the response is shown below."
        } else {
            try {
                outputProvider.speak(speech)
                ""
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                " Spoken playback was unavailable; the response is shown below."
            }
        }
        return PluginActionOutput(
            title = "Voice assistant response",
            content = "You said: $transcript\n\n$response\n\n$speechNote".trim()
        )
    }

    companion object {
        const val PLUGIN_ID = "one.voice.interface"
        const val VOICE_ACTION = "voice_interaction"
    }
}
