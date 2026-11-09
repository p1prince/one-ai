package com.oneai.app.ai

import org.json.JSONArray
import org.json.JSONObject

/**
 * Native Anthropic Messages adapter. The key goes in `x-api-key`; `anthropic-version` is pinned. System messages become the
 * top-level `system` field, consecutive turns from the same role are merged, and a conversation never starts with an
 * assistant turn, because the Messages API rejects each of those shapes.
 */
class AnthropicProvider(
    private val profile: AiProviderProfile,
    private val secrets: ProfileSecrets,
    private val http: AiHttp = HttpUrlConnectionAiHttp()
) : AiProvider {
    override suspend fun complete(messages: List<AiMessage>): AiCompletion {
        ProviderSupport.checkMessages(messages)
        return ProviderSupport.io {
            val key = ProviderSupport.readKey(secrets, profile, required = true)!!
            val endpoint = ProviderSupport.endpointOf(profile)
            val request = AiHttpRequest(
                endpoint,
                mapOf(
                    "x-api-key" to key,
                    "anthropic-version" to API_VERSION,
                    "Content-Type" to "application/json; charset=utf-8",
                    "Accept" to "application/json",
                    "User-Agent" to "ONE-AI-Android"
                ),
                requestBody(messages, profile.model.trim()).toString()
            )
            http.open(request).use { response ->
                val raw = ProviderSupport.body(response)
                if (response.status !in 200..299) throw ProviderSupport.httpFailure(response.status, raw, profile.name, key)
                parse(raw, profile.model.trim(), profile.name)
            }
        }
    }

    companion object {
        const val API_VERSION = "2023-06-01"
        const val MAX_TOKENS = 4096

        /** Pure: system text, then alternating user/assistant turns that start with a user turn. */
        fun requestBody(messages: List<AiMessage>, model: String): JSONObject {
            val system = messages.filter { it.role == "system" }.joinToString("\n\n") { it.content }
            val turns = ArrayList<AiMessage>()
            messages.filter { it.role != "system" }.forEach { next ->
                val last = turns.lastOrNull()
                if (last != null && last.role == next.role) turns[turns.lastIndex] = AiMessage(last.role, last.content + "\n\n" + next.content)
                else turns += next
            }
            while (turns.isNotEmpty() && turns.first().role != "user") turns.removeAt(0)
            if (turns.isEmpty()) throw AiProviderException("INVALID_REQUEST", "Claude needs at least one user message.")
            val body = JSONObject().put("model", model).put("max_tokens", MAX_TOKENS)
            if (system.isNotBlank()) body.put("system", system)
            body.put("messages", JSONArray().apply { turns.forEach { put(JSONObject().put("role", it.role).put("content", it.content)) } })
            return body
        }

        /** Pure: a successful Messages response as a normalized completion, or a normalized failure. */
        fun parse(raw: String, fallbackModel: String, providerName: String): AiCompletion {
            val json = ProviderSupport.json(raw)
            val blocks = json.optJSONArray("content")
            val text = buildString {
                if (blocks != null) for (i in 0 until blocks.length()) {
                    val block = blocks.optJSONObject(i) ?: continue
                    if (block.optString("type") == "text") {
                        val part = block.optString("text")
                        if (part.isNotBlank()) {
                            if (isNotEmpty()) append("\n")
                            append(part)
                        }
                    }
                }
            }
            if (text.isBlank()) {
                if (json.optString("stop_reason") == "refusal") throw AiProviderException("BLOCKED_BY_PROVIDER", "Claude declined to answer this request.")
                throw AiProviderException("EMPTY_RESPONSE", if (blocks == null) "The provider returned no completion." else "The provider returned an empty answer.")
            }
            return AiCompletion(text, json.optString("model").ifBlank { fallbackModel }, providerName)
        }
    }
}
