package com.oneai.app.ai

import org.json.JSONArray
import org.json.JSONObject

/**
 * Native Google Gemini adapter (`generateContent`). The key travels in the `x-goog-api-key` header, never in the URL,
 * so it cannot end up in a logged address. System messages become `systemInstruction`; assistant turns use Gemini's
 * `model` role. The profile endpoint is the API base (for example `https://generativelanguage.googleapis.com/v1beta`).
 */
class GeminiProvider(
    private val profile: AiProviderProfile,
    private val secrets: ProfileSecrets,
    private val http: AiHttp = HttpUrlConnectionAiHttp()
) : AiProvider {
    override suspend fun complete(messages: List<AiMessage>): AiCompletion {
        ProviderSupport.checkMessages(messages)
        return ProviderSupport.io {
            val key = ProviderSupport.readKey(secrets, profile, required = true)!!
            val base = ProviderSupport.endpointOf(profile)
            ProviderCatalog.modelError(ProviderCatalog.GEMINI, profile.model)?.let { throw AiProviderException("INVALID_MODEL", it) }
            val model = modelId(profile.model)
            val request = AiHttpRequest(
                ProviderSupport.join(base, "models/$model:generateContent"),
                mapOf(
                    "x-goog-api-key" to key,
                    "Content-Type" to "application/json; charset=utf-8",
                    "Accept" to "application/json",
                    "User-Agent" to "ONE-AI-Android"
                ),
                requestBody(messages).toString()
            )
            http.open(request).use { response ->
                val raw = ProviderSupport.body(response)
                if (response.status !in 200..299) throw ProviderSupport.httpFailure(response.status, raw, profile.name, key)
                parse(raw, model, profile.name)
            }
        }
    }

    companion object {
        fun modelId(model: String): String = model.trim().removePrefix("models/")

        /** Pure: the JSON body for [messages]. */
        fun requestBody(messages: List<AiMessage>): JSONObject {
            val system = messages.filter { it.role == "system" }.joinToString("\n\n") { it.content }
            val body = JSONObject()
            if (system.isNotBlank()) {
                body.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            }
            body.put("contents", JSONArray().apply {
                messages.filter { it.role != "system" }.forEach {
                    put(
                        JSONObject()
                            .put("role", if (it.role == "assistant") "model" else "user")
                            .put("parts", JSONArray().put(JSONObject().put("text", it.content)))
                    )
                }
            })
            body.put("generationConfig", JSONObject().put("temperature", 0.4))
            return body
        }

        /** Pure: a successful Gemini response as a normalized completion, or a normalized failure. */
        fun parse(raw: String, fallbackModel: String, providerName: String): AiCompletion {
            val json = ProviderSupport.json(raw)
            val candidate = json.optJSONArray("candidates")?.optJSONObject(0)
            val text = buildString {
                val parts = candidate?.optJSONObject("content")?.optJSONArray("parts")
                if (parts != null) for (i in 0 until parts.length()) append(parts.optJSONObject(i)?.optString("text").orEmpty())
            }
            if (text.isBlank()) {
                val blocked = json.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
                if (blocked.isNotBlank()) throw AiProviderException("BLOCKED_BY_PROVIDER", "Gemini blocked the request ($blocked).")
                val finish = candidate?.optString("finishReason").orEmpty()
                if (finish.isNotBlank() && finish != "STOP") {
                    throw AiProviderException("BLOCKED_BY_PROVIDER", "Gemini returned no answer (finish reason: $finish).")
                }
                throw AiProviderException(
                    "EMPTY_RESPONSE",
                    if (candidate == null) "The provider returned no completion." else "The provider returned an empty answer."
                )
            }
            return AiCompletion(text, json.optString("modelVersion").ifBlank { fallbackModel }, providerName)
        }
    }
}
