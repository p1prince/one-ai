package com.oneai.app.plugins

import com.oneai.app.ai.AiActivityLogger
import com.oneai.app.ai.AiCompletion
import com.oneai.app.ai.AiMessage
import com.oneai.app.ai.AiOrchestrator
import com.oneai.app.ai.AiProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceInterfaceAdapterTest {
    @Test fun capturesTranscriptRoutesItThroughSharedAiAndSpeaksResponse() = runBlocking {
        var prompt = ""
        var spoken = ""
        val ai = object : AiProvider {
            override suspend fun complete(messages: List<AiMessage>): AiCompletion {
                prompt = messages.last().content
                return AiCompletion("Hello from ONE AI.", "test-model", "test-provider")
            }
        }
        val adapter = VoiceInterfaceAdapter(
            VoiceInputProvider { "  What is ONE AI?  " },
            VoiceOutputProvider { spoken = it },
            AiOrchestrator(ai, AiActivityLogger {})
        )
        val output = adapter.execute(
            PluginActionInput(VoiceInterfaceAdapter.VOICE_ACTION, "microphone", "Test one voice session")
        )
        assertEquals("What is ONE AI?", prompt)
        assertEquals("Hello from ONE AI.", spoken)
        assertTrue(output.content.contains("You said: What is ONE AI?"))
        assertTrue(output.content.contains("Hello from ONE AI."))
    }

    @Test fun stillReturnsTextWhenSpeechPlaybackIsUnavailable() = runBlocking {
        val ai = object : AiProvider {
            override suspend fun complete(messages: List<AiMessage>) =
                AiCompletion("Text response is available.", "test-model", "test-provider")
        }
        val adapter = VoiceInterfaceAdapter(
            VoiceInputProvider { "Read this aloud" },
            VoiceOutputProvider { throw IllegalStateException("No TTS engine") },
            AiOrchestrator(ai, AiActivityLogger {})
        )
        val output = adapter.execute(
            PluginActionInput(VoiceInterfaceAdapter.VOICE_ACTION, "microphone", "Test one voice session")
        )
        assertTrue(output.content.contains("Text response is available."))
        assertTrue(output.content.contains("Spoken playback was unavailable"))
    }

    @Test fun rejectsUnsupportedVoiceAction() = runBlocking {
        val ai = object : AiProvider {
            override suspend fun complete(messages: List<AiMessage>) =
                AiCompletion("Unused", "test-model", "test-provider")
        }
        val adapter = VoiceInterfaceAdapter(
            VoiceInputProvider { "Unused transcript" },
            VoiceOutputProvider {},
            AiOrchestrator(ai, AiActivityLogger {})
        )
        val rejected = runCatching {
            adapter.execute(PluginActionInput("other_action", "microphone", "Invalid action"))
        }
        assertTrue(rejected.isFailure)
    }
}
