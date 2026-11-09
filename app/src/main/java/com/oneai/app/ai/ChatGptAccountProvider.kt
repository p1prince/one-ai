package com.oneai.app.ai

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Adapter for the ChatGPT-hosted Responses backend used by a signed-in ChatGPT account (recovered from the Orbit-style
 * implementation in `p1prince/one-ai-android`; its streaming transport and 401 handling are kept).
 *
 * Isolation: the access token goes only to the fixed [ProviderCatalog.CHATGPT_RESPONSES_ENDPOINT], never to the
 * profile's endpoint field, and this class never reads an API key. The OpenAI API-key adapter in turn never sees the
 * account tokens. A 401 triggers exactly one refresh and one retry; a second 401 asks the user to reconnect.
 */
class ChatGptAccountProvider(
    private val profile: AiProviderProfile,
    private val account: ChatGptAccount,
    private val http: AiHttp = HttpUrlConnectionAiHttp(),
    private val newSessionId: () -> String = { UUID.randomUUID().toString() }
) : AiProvider {
    override suspend fun complete(messages: List<AiMessage>): AiCompletion {
        ProviderSupport.checkMessages(messages)
        val first = account.accessToken()
        val text = try {
            requestOnce(messages, first)
        } catch (error: AiProviderException) {
            if (error.code != "HTTP_401") throw error
            val second = account.accessToken(rejectedToken = first)
            try {
                requestOnce(messages, second)
            } catch (again: AiProviderException) {
                if (again.code != "HTTP_401") throw again
                throw AiProviderException("SESSION_EXPIRED", "ChatGPT rejected the refreshed sign-in. Reconnect ChatGPT in Settings.")
            }
        }
        return AiCompletion(text, profile.model.trim(), profile.name)
    }

    private suspend fun requestOnce(messages: List<AiMessage>, accessToken: String): String = ProviderSupport.io {
        val model = profile.model.trim()
        if (model.isEmpty()) throw AiProviderException("INVALID_MODEL", "Enter a model name for the ChatGPT account.")
        val headers = LinkedHashMap<String, String>().apply {
            put("Content-Type", "application/json")
            put("Accept", "text/event-stream")
            put("Authorization", "Bearer $accessToken")
            put("Originator", "one-ai")
            put("User-Agent", ChatGptAccount.USER_AGENT)
            put("session_id", newSessionId())
            account.accountId()?.let { put("ChatGPT-Account-ID", it) }
        }
        val request = AiHttpRequest(
            ProviderCatalog.CHATGPT_RESPONSES_ENDPOINT, headers, requestBody(messages, model).toString(),
            readTimeoutMs = AiHttpLimits.STREAM_READ_TIMEOUT_MS
        )
        http.open(request).use { response ->
            if (response.status !in 200..299) {
                throw ProviderSupport.httpFailure(response.status, response.readBody(ProviderSupport.MAX_RESPONSE_BYTES), profile.name, accessToken)
            }
            parseStream(response.lines())
        }
    }

    companion object {
        const val DEFAULT_INSTRUCTIONS = "You are ONE AI, a helpful and accurate personal assistant. Answer the user's request clearly and honestly."

        /** Pure: system turns become `instructions`; user and assistant turns become Responses input items. */
        fun requestBody(messages: List<AiMessage>, model: String): JSONObject {
            val instructions = messages.filter { it.role == "system" }.joinToString("\n\n") { it.content }.ifBlank { DEFAULT_INSTRUCTIONS }
            val input = JSONArray()
            messages.filter { it.role != "system" }.forEach { message ->
                val type = if (message.role == "assistant") "output_text" else "input_text"
                input.put(
                    JSONObject().put("role", message.role).put(
                        "content", JSONArray().put(JSONObject().put("type", type).put("text", message.content))
                    )
                )
            }
            return JSONObject()
                .put("model", model)
                .put("instructions", instructions)
                .put("store", false)
                .put("stream", true)
                .put("parallel_tool_calls", false)
                .put("input", input)
        }

        /**
         * Pure: reads a `text/event-stream` of Responses events and returns the full answer. The stream must end with
         * `response.completed`; a failure event, an `error` event, a premature end or an empty answer are normalized errors.
         */
        fun parseStream(lines: Sequence<String>): String {
            val answer = StringBuilder()
            var completed = false
            for (line in lines) {
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data.isBlank() || data == "[DONE]") continue
                val event = runCatching { JSONObject(data) }.getOrNull() ?: continue
                when (event.optString("type")) {
                    "response.output_text.delta" -> answer.append(event.optString("delta"))
                    "response.failed" -> {
                        val error = event.optJSONObject("response")?.optJSONObject("error")
                        val code = error?.optString("code").orEmpty()
                        val message = error?.optString("message").orEmpty().ifBlank { "The response could not be completed." }
                        throw AiProviderException("PROVIDER_ERROR", "ChatGPT request failed" + (if (code.isNotBlank()) " ($code)" else "") + ": " + message.take(300))
                    }
                    "response.incomplete" -> {
                        val reason = event.optJSONObject("response")?.optJSONObject("incomplete_details")?.optString("reason").orEmpty()
                        throw AiProviderException("PROVIDER_ERROR", "ChatGPT stopped before finishing" + if (reason.isNotBlank()) " ($reason)." else ".")
                    }
                    "response.completed" -> completed = true
                    "error" -> {
                        val detail = ProviderSupport.errorDetail(JSONObject().put("error", event.opt("error") ?: event.optString("message")).toString())
                            ?: event.optString("message").ifBlank { "ChatGPT returned an error." }
                        throw AiProviderException("PROVIDER_ERROR", detail.take(300))
                    }
                }
            }
            if (!completed) throw AiProviderException("INVALID_RESPONSE", "ChatGPT's response stream ended before completion. Please retry.")
            return answer.toString().trim().ifBlank { throw AiProviderException("EMPTY_RESPONSE", "ChatGPT completed the request but returned no readable text.") }
        }
    }
}
