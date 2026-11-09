package com.oneai.app.ai

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * Ollama adapter over its native `/api/chat` endpoint (non-streaming). No key is needed; if the user saved one (for a
 * server behind an authenticating proxy) it is sent as a bearer token. The endpoint must be HTTPS, or plain HTTP to this
 * device only; that rule is enforced here as well as in Settings, so a hand-edited profile cannot send prompts in clear
 * over a network.
 */
class OllamaProvider(
    private val profile: AiProviderProfile,
    private val secrets: ProfileSecrets,
    private val http: AiHttp = HttpUrlConnectionAiHttp()
) : AiProvider {
    override suspend fun complete(messages: List<AiMessage>): AiCompletion {
        ProviderSupport.checkMessages(messages)
        return ProviderSupport.io {
            val key = ProviderSupport.readKey(secrets, profile, required = false)
            val base = ProviderSupport.endpointOf(profile)
            val model = profile.model.trim()
            if (model.isEmpty()) throw AiProviderException("INVALID_MODEL", "Enter the name of an Ollama model.")
            val headers = LinkedHashMap<String, String>().apply {
                put("Content-Type", "application/json; charset=utf-8")
                put("Accept", "application/json")
                put("User-Agent", "ONE-AI-Android")
                if (key != null) put("Authorization", "Bearer $key")
            }
            val request = AiHttpRequest(chatUrl(base), headers, requestBody(messages, model).toString(), readTimeoutMs = AiHttpLimits.STREAM_READ_TIMEOUT_MS)
            try {
                http.open(request).use { response ->
                    val raw = ProviderSupport.body(response)
                    if (response.status !in 200..299) throw ProviderSupport.httpFailure(response.status, raw, profile.name, key)
                    parse(raw, model, profile.name)
                }
            } catch (error: java.io.IOException) {
                val failure = ProviderSupport.networkFailure(error)
                if (failure.code != "CONNECTION_FAILED") throw failure
                throw unreachable(base, failure)
            } catch (error: AiProviderException) {
                if (error.code != "CONNECTION_FAILED") throw error
                throw unreachable(base, error)
            }
        }
    }

    private fun unreachable(base: String, cause: Throwable): AiProviderException {
        val host = runCatching { URI(base).authority }.getOrNull() ?: "the configured address"
        return AiProviderException("CONNECTION_FAILED", "Could not reach Ollama at $host. Make sure it is running and the address is right.", cause)
    }

    companion object {
        /** `<base>/api/chat`, unless the user already pasted the full path. */
        fun chatUrl(base: String): String {
            val clean = base.trim().trimEnd('/')
            return if (clean.endsWith("/api/chat")) clean else "$clean/api/chat"
        }

        /** Pure: the JSON body for [messages]. */
        fun requestBody(messages: List<AiMessage>, model: String): JSONObject =
            JSONObject()
                .put("model", model)
                .put("stream", false)
                .put("messages", JSONArray().apply { messages.forEach { put(JSONObject().put("role", it.role).put("content", it.content)) } })
                .put("options", JSONObject().put("temperature", 0.4))

        /** Pure: a successful `/api/chat` response as a normalized completion, or a normalized failure. */
        fun parse(raw: String, fallbackModel: String, providerName: String): AiCompletion {
            val json = ProviderSupport.json(raw)
            val error = json.optString("error")
            if (error.isNotBlank()) throw AiProviderException("PROVIDER_ERROR", error.take(300))
            val message = json.optJSONObject("message")
                ?: throw AiProviderException("INVALID_RESPONSE", "The provider response did not contain a message.")
            val text = message.optString("content")
            if (text.isBlank()) throw AiProviderException("EMPTY_RESPONSE", "The provider returned an empty answer.")
            return AiCompletion(text, json.optString("model").ifBlank { fallbackModel }, providerName)
        }
    }
}
