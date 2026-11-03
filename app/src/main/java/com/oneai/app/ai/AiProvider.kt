package com.oneai.app.ai

/** Minimal activity sink so the orchestration layer is testable without Android storage. */
fun interface AiActivityLogger {
    fun log(message: String)
}

/** Provider-neutral message passed between ONE AI and an AI service. */
data class AiMessage(val role: String, val content: String)

/** Normalized completion returned to the rest of ONE AI. */
data class AiCompletion(val content: String, val model: String, val provider: String)

/** Stable contract that allows additional AI providers without changing app features. */
interface AiProvider {
    suspend fun complete(messages: List<AiMessage>): AiCompletion
}

/** Safe, machine-readable provider failure. Credentials must never be included. */
class AiProviderException(
    val code: String,
    message: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause)
