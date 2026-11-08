package com.oneai.app.ai

import com.oneai.app.core.OneAiStore
import com.oneai.app.security.CredentialStatus
import com.oneai.app.security.SecretRead
import com.oneai.app.security.SecretVault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** OpenAI-compatible chat-completions adapter. Credentials are never logged. */
class AiProviderClient(
    private val store: OneAiStore,
    private val vault: SecretVault
) : AiProvider {
    suspend fun ask(prompt: String): String {
        require(prompt.isNotBlank()) { "Enter a message before sending it." }
        return complete(listOf(AiMessage("user", prompt.trim()))).content
    }

    override suspend fun complete(messages: List<AiMessage>): AiCompletion = withContext(Dispatchers.IO) {
        require(messages.isNotEmpty()) { "At least one message is required." }
        require(messages.all { it.role in ALLOWED_ROLES && it.content.isNotBlank() }) {
            "Messages must have a supported role and non-empty content."
        }
        val stored = vault.readResult()
        if (stored === SecretRead.Unreadable) throw AiProviderException("CREDENTIALS_UNREADABLE", CredentialStatus.UNREADABLE_MESSAGE)
        val key = (stored as? SecretRead.Present)?.value?.takeIf { it.isNotBlank() }
            ?: throw AiProviderException("MISSING_CREDENTIALS", "Add an API key in Settings before using the AI provider.")
        val endpoint = store.aiEndpoint().trim()
        val url = runCatching { URL(endpoint) }.getOrElse {
            throw AiProviderException("INVALID_ENDPOINT", "The provider endpoint is not a valid URL.")
        }
        require(url.protocol.equals("https", ignoreCase = true)) { "Provider endpoint must use HTTPS." }
        require(url.host.isNotBlank()) { "Provider endpoint must include a host." }
        require(url.userInfo == null && url.ref == null) { "Provider endpoint cannot contain embedded credentials or a fragment." }

        val connection = url.openConnection() as? HttpURLConnection
            ?: throw AiProviderException("CONNECTION_FAILED", "Could not open the provider connection.")
        try {
            connection.instanceFollowRedirects = false
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $key")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "ONE-AI-Android")

            val payload = JSONObject()
                .put("model", store.aiModel())
                .put("messages", JSONArray().apply {
                    messages.forEach { put(JSONObject().put("role", it.role).put("content", it.content)) }
                })
                .put("temperature", 0.4)
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.use { AiResponseBodyReader.readUtf8(it, MAX_RESPONSE_BYTES) }
                ?: if (stream == null) "" else throw AiProviderException(
                    "RESPONSE_TOO_LARGE",
                    "The AI provider response exceeded the 1 MiB safety limit."
                )
            if (status !in 200..299) {
                val detail = runCatching { JSONObject(raw).optJSONObject("error")?.optString("message") }
                    .getOrNull()?.takeIf { it.isNotBlank() }
                throw AiProviderException("HTTP_$status", detail?.take(300) ?: "The AI provider returned HTTP $status.")
            }

            val json = runCatching { JSONObject(raw) }.getOrElse {
                throw AiProviderException("INVALID_RESPONSE", "The provider returned an unreadable response.")
            }
            val choice = json.optJSONArray("choices")?.optJSONObject(0)
                ?: throw AiProviderException("EMPTY_RESPONSE", "The provider returned no completion.")
            val message = choice.optJSONObject("message")
                ?: throw AiProviderException("INVALID_RESPONSE", "The provider response did not contain a message.")
            val answer = extractContent(message.opt("content"))
            if (answer.isBlank()) throw AiProviderException("EMPTY_RESPONSE", "The provider returned an empty answer.")
            AiCompletion(answer, json.optString("model", store.aiModel()), store.aiProviderName())
        } catch (e: AiProviderException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw AiProviderException("REQUEST_FAILED", e.message?.take(250) ?: "The AI request failed.", e)
        } finally {
            connection.disconnect()
        }
    }

    private fun extractContent(value: Any?): String = when (value) {
        is String -> value
        is JSONArray -> buildString {
            for (index in 0 until value.length()) {
                val text = value.optJSONObject(index)?.optString("text").orEmpty()
                if (text.isNotBlank()) {
                    if (isNotEmpty()) append("\n")
                    append(text)
                }
            }
        }
        else -> ""
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val MAX_RESPONSE_BYTES = 1024 * 1024
        private val ALLOWED_ROLES = setOf("system", "user", "assistant")
    }
}
