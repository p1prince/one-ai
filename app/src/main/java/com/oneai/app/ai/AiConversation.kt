package com.oneai.app.ai

/**
 * In-memory conversation history (user/assistant turns only). It is deliberately not persisted:
 * message text is never written to storage or the activity log. Bounded to avoid unbounded growth.
 */
class AiConversation {
    private val turns = ArrayList<AiMessage>()

    @Synchronized fun history(): List<AiMessage> = turns.toList()

    @Synchronized fun messageCount(): Int = turns.size

    /** Records a completed exchange; call only after the provider answered successfully. */
    @Synchronized fun append(user: AiMessage, assistant: AiMessage) {
        turns += user
        turns += assistant
        while (turns.size > MAX_STORED_MESSAGES) turns.removeAt(0)
        while (turns.isNotEmpty() && turns.first().role != "user") turns.removeAt(0)
    }

    @Synchronized fun clear() { turns.clear() }

    companion object { const val MAX_STORED_MESSAGES = 200 }
}

/** Decides which part of a conversation is sent to the provider. Pure and deterministic. */
object ContextPolicy {
    const val MAX_CONTEXT_CHARS = 24_000
    const val MAX_MESSAGES = 40

    /**
     * Keeps the most recent messages within the character and message budgets, always keeps the
     * newest message, and never starts with an assistant message.
     */
    fun window(history: List<AiMessage>): List<AiMessage> {
        val kept = ArrayDeque<AiMessage>()
        var total = 0
        for (message in history.asReversed()) {
            if (kept.size >= MAX_MESSAGES) break
            if (kept.isNotEmpty() && total + message.content.length > MAX_CONTEXT_CHARS) break
            kept.addFirst(message)
            total += message.content.length
        }
        while (kept.isNotEmpty() && kept.first().role != "user") kept.removeFirst()
        return kept.toList()
    }
}
