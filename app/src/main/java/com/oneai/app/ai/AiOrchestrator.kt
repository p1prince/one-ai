package com.oneai.app.ai

import kotlinx.coroutines.CancellationException

/**
 * Shared entry point for AI requests from screens, commands and the bubble.
 * This initial layer normalizes requests and records outcomes. Android actions
 * will be dispatched only through separately registered, permissioned tools.
 */
class AiOrchestrator(
    private val provider: AiProvider,
    private val activity: AiActivityLogger
) {
    suspend fun respond(prompt: String): AiCompletion {
        val cleanPrompt = prompt.trim()
        require(cleanPrompt.isNotEmpty()) { "Enter a message before sending it." }
        require(cleanPrompt.length <= MAX_PROMPT_LENGTH) {
            "The message is too long. Maximum length is $MAX_PROMPT_LENGTH characters."
        }

        activity.log("AI request started")
        return try {
            val completion = provider.complete(
                listOf(
                    AiMessage(
                        "system",
                        "You are ONE AI, a helpful assistant. You may explain and plan actions, " +
                            "but do not claim to have changed device settings, controlled apps, accessed screens, " +
                            "or completed external actions unless an explicitly available tool confirms it."
                    ),
                    AiMessage("user", cleanPrompt)
                )
            )
            activity.log("AI response received (" + completion.provider + ")")
            completion
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            activity.log("AI request failed: " + (error.message?.take(120) ?: "Unknown error"))
            throw error
        }
    }

    companion object {
        private const val MAX_PROMPT_LENGTH = 20_000
    }
}
