package com.oneai.app.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AiOrchestratorTest {
    private class RecordingProvider : AiProvider {
        var received: List<AiMessage> = emptyList()
        override suspend fun complete(messages: List<AiMessage>): AiCompletion {
            received = messages
            return AiCompletion("Hello from the test provider", "test-model", "test-provider")
        }
    }

    private class RecordingLogger : AiActivityLogger {
        val entries = mutableListOf<String>()
        override fun log(message: String) { entries += message }
    }

    @Test
    fun respondPassesSystemAndUserMessagesAndLogsSuccess() = runBlocking {
        val provider = RecordingProvider()
        val logger = RecordingLogger()
        val result = AiOrchestrator(provider, logger).respond("  Help me plan  ")

        assertEquals("Hello from the test provider", result.content)
        assertEquals("system", provider.received[0].role)
        assertEquals("user", provider.received[1].role)
        assertEquals("Help me plan", provider.received[1].content)
        assertEquals(listOf("AI request started", "AI response received (test-provider)"), logger.entries)
    }

    @Test
    fun respondRejectsBlankPromptBeforeCallingProvider() = runBlocking {
        val provider = RecordingProvider()
        val logger = RecordingLogger()

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { AiOrchestrator(provider, logger).respond("  ") }
        }
        assertEquals(emptyList<AiMessage>(), provider.received)
        assertEquals(emptyList<String>(), logger.entries)
    }

    @Test
    fun respondRejectsOversizedPrompt() = runBlocking {
        val provider = RecordingProvider()
        val logger = RecordingLogger()
        val oversized = "x".repeat(20_001)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { AiOrchestrator(provider, logger).respond(oversized) }
        }
        assertEquals(emptyList<AiMessage>(), provider.received)
    }
}
