package com.oneai.app.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AiConversationTest {
    private class ScriptedProvider(private val answers: MutableList<Result<String>>) : AiProvider {
        val calls = mutableListOf<List<AiMessage>>()
        override suspend fun complete(messages: List<AiMessage>): AiCompletion {
            calls += messages
            return AiCompletion(answers.removeAt(0).getOrThrow(), "m", "p")
        }
    }
    private val logger = AiActivityLogger { }
    private fun u(t: String) = AiMessage("user", t)
    private fun a(t: String) = AiMessage("assistant", t)

    @Test fun laterRequestsIncludeEarlierTurnsInOrder() = runBlocking {
        val provider = ScriptedProvider(mutableListOf(Result.success("one"), Result.success("two")))
        val orchestrator = AiOrchestrator(provider, logger)
        val conversation = AiConversation()
        orchestrator.respond("first", conversation)
        orchestrator.respond("second", conversation)
        assertEquals(listOf("system", "user", "assistant", "user"), provider.calls[1].map { it.role })
        assertEquals(listOf("first", "one", "second"), provider.calls[1].drop(1).map { it.content })
        assertEquals(4, conversation.messageCount())
    }

    @Test fun failedRequestIsNotRecordedSoRetryDoesNotDuplicateTheUserTurn() = runBlocking {
        val provider = ScriptedProvider(mutableListOf(Result.failure(AiProviderException("HTTP_400", "bad")), Result.success("ok")))
        val orchestrator = AiOrchestrator(provider, logger)
        val conversation = AiConversation()
        assertThrows(AiProviderException::class.java) { runBlocking { orchestrator.respond("hello", conversation) } }
        assertEquals(0, conversation.messageCount())
        orchestrator.respond("hello", conversation)
        assertEquals(2, conversation.messageCount())
    }

    @Test fun withoutAConversationRequestsRemainSingleShot() = runBlocking {
        val provider = ScriptedProvider(mutableListOf(Result.success("x")))
        AiOrchestrator(provider, logger).respond("only")
        assertEquals(listOf("system", "user"), provider.calls.single().map { it.role })
    }

    @Test fun clearStartsAFreshConversation() = runBlocking {
        val provider = ScriptedProvider(mutableListOf(Result.success("one"), Result.success("two")))
        val orchestrator = AiOrchestrator(provider, logger)
        val conversation = AiConversation()
        orchestrator.respond("first", conversation)
        conversation.clear()
        orchestrator.respond("second", conversation)
        assertEquals(listOf("system", "user"), provider.calls[1].map { it.role })
    }

    @Test fun messageTextIsNotWrittenToTheActivityLog() = runBlocking {
        val entries = mutableListOf<String>()
        val provider = ScriptedProvider(mutableListOf(Result.success("private answer")))
        AiOrchestrator(provider, AiActivityLogger { entries += it }).respond("private question", AiConversation())
        assertTrue(entries.none { it.contains("private") })
    }

    @Test fun contextWindowKeepsNewestMessagesWithinTheCharacterBudget() {
        val big = "x".repeat(10_000)
        val history = listOf(u("old"), a(big), u(big), a(big), u("new"))
        val window = ContextPolicy.window(history)
        assertEquals("new", window.last().content)
        assertTrue(window.sumOf { it.content.length } <= ContextPolicy.MAX_CONTEXT_CHARS)
        assertEquals("user", window.first().role)
        assertFalse(window.any { it.content == "old" })
    }

    @Test fun contextWindowRespectsMessageCountAndNeverStartsWithAssistant() {
        val history = (1..100).flatMap { listOf(u("q$it"), a("r$it")) } + u("latest")
        val window = ContextPolicy.window(history)
        assertTrue(window.size <= ContextPolicy.MAX_MESSAGES)
        assertEquals("user", window.first().role)
        assertEquals("latest", window.last().content)
    }

    @Test fun anOversizedNewestMessageIsStillSent() {
        val huge = "y".repeat(ContextPolicy.MAX_CONTEXT_CHARS + 5)
        assertEquals(listOf(huge), ContextPolicy.window(listOf(u("a"), a("b"), u(huge))).map { it.content })
    }

    @Test fun storedHistoryIsBoundedAndStartsWithAUserTurn() {
        val conversation = AiConversation()
        repeat(150) { conversation.append(u("q$it"), a("r$it")) }
        assertTrue(conversation.messageCount() <= AiConversation.MAX_STORED_MESSAGES)
        assertEquals("user", conversation.history().first().role)
        assertEquals("r149", conversation.history().last().content)
    }

    // ---- retry / error recovery ----
    private class FlakyProvider(private val failures: List<Throwable>, private val reply: String = "ok") : AiProvider {
        var calls = 0
        override suspend fun complete(messages: List<AiMessage>): AiCompletion {
            calls++
            if (calls <= failures.size) throw failures[calls - 1]
            return AiCompletion(reply, "m", "p")
        }
    }

    @Test fun transientFailuresAreRetriedWithExponentialBackoff() = runBlocking {
        val delays = mutableListOf<Long>()
        val flaky = FlakyProvider(listOf(AiProviderException("HTTP_503", "x"), AiProviderException("HTTP_429", "y")))
        val result = RetryingAiProvider(flaky, 3, 500) { delays += it }.complete(listOf(u("hi")))
        assertEquals("ok", result.content)
        assertEquals(3, flaky.calls)
        assertEquals(listOf(500L, 1000L), delays)
    }

    @Test fun permanentFailuresAreNotRetried() = runBlocking {
        listOf("MISSING_CREDENTIALS", "INVALID_ENDPOINT", "HTTP_401", "HTTP_400", "RESPONSE_TOO_LARGE", "EMPTY_RESPONSE").forEach { code ->
            val flaky = FlakyProvider(listOf(AiProviderException(code, "x")))
            assertThrows(AiProviderException::class.java) { runBlocking { RetryingAiProvider(flaky, 3, 1) { }.complete(listOf(u("hi"))) } }
            assertEquals(code, 1, flaky.calls)
        }
    }

    @Test fun exhaustedRetriesRethrowTheLastError() = runBlocking {
        val flaky = FlakyProvider(List(5) { AiProviderException("CONNECTION_FAILED", "down") })
        val error = assertThrows(AiProviderException::class.java) { runBlocking { RetryingAiProvider(flaky, 3, 1) { }.complete(listOf(u("hi"))) } }
        assertEquals("CONNECTION_FAILED", error.code)
        assertEquals(3, flaky.calls)
    }

    @Test fun cancellationIsNeverRetried() = runBlocking {
        val flaky = FlakyProvider(listOf(CancellationException("cancelled")))
        assertThrows(CancellationException::class.java) { runBlocking { RetryingAiProvider(flaky, 3, 1) { }.complete(listOf(u("hi"))) } }
        assertEquals(1, flaky.calls)
    }

    @Test fun transientCodeClassification() {
        assertTrue(RetryingAiProvider.isTransient("HTTP_500")); assertTrue(RetryingAiProvider.isTransient("HTTP_429"))
        assertTrue(RetryingAiProvider.isTransient("REQUEST_FAILED")); assertFalse(RetryingAiProvider.isTransient("HTTP_404"))
        assertFalse(RetryingAiProvider.isTransient("HTTP_5000"))
    }
}
