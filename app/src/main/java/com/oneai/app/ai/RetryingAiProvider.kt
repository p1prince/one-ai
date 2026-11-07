package com.oneai.app.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Error recovery for transient provider failures. Retries only network/rate-limit/server errors
 * with exponential backoff; configuration, credential, validation and size errors fail at once.
 * Cancellation is never retried.
 */
class RetryingAiProvider(
    private val delegate: AiProvider,
    private val maxAttempts: Int = 3,
    private val baseDelayMs: Long = 500,
    private val pause: suspend (Long) -> Unit = { delay(it) }
) : AiProvider {
    init { require(maxAttempts >= 1) { "maxAttempts must be at least 1" } }

    override suspend fun complete(messages: List<AiMessage>): AiCompletion {
        var attempt = 1
        while (true) {
            try {
                return delegate.complete(messages)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: AiProviderException) {
                if (attempt >= maxAttempts || !isTransient(error.code)) throw error
                pause(baseDelayMs shl (attempt - 1))
                attempt++
            }
        }
    }

    companion object {
        fun isTransient(code: String): Boolean =
            code == "CONNECTION_FAILED" || code == "REQUEST_FAILED" || code == "HTTP_429" ||
                (code.startsWith("HTTP_5") && code.length == 8)
    }
}
