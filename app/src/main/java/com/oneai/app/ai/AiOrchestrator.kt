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
    /**
     * Answers [prompt]. With a [conversation], earlier turns are included (bounded by
     * [ContextPolicy]) and the exchange is recorded only after a successful answer.
     */
    suspend fun respond(prompt: String, conversation: AiConversation? = null): AiCompletion {
        val cleanPrompt = prompt.trim()
        require(cleanPrompt.isNotEmpty()) { "Enter a message before sending it." }
        require(cleanPrompt.length <= MAX_PROMPT_LENGTH) {
            "The message is too long. Maximum length is $MAX_PROMPT_LENGTH characters."
        }

        activity.log("AI request started")
        return try {
            val userMessage = AiMessage("user", cleanPrompt)
            val context = if (conversation == null) listOf(userMessage)
                else ContextPolicy.window(conversation.history() + userMessage)
            val completion = provider.complete(listOf(SYSTEM_MESSAGE) + context)
            conversation?.append(userMessage, AiMessage("assistant", completion.content))
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
        private val SYSTEM_MESSAGE = AiMessage(
            "system",
            "You are ONE AI, a helpful assistant. You may explain and plan actions, " +
                "but do not claim to have changed device settings, controlled apps, accessed screens, " +
                "or completed external actions unless an explicitly available tool confirms it."
        )
    }
}
