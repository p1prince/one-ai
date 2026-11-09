package com.oneai.app.ai

import com.oneai.app.security.CredentialStatus
import com.oneai.app.security.SecretRead
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenAI-compatible chat-completions adapter for one provider profile (decision D-V / V1, slice S1).
 * It serves OpenAI with an API key, DeepSeek, Groq, OpenRouter and any compatible custom endpoint: the endpoint, model
 * and display name come from [profile]; the key is read from [secrets] for that profile only.
 * Credentials are never logged. All network traffic goes through [http], so tests need no real key.
 */
class AiProviderClient(
    private val profile: AiProviderProfile,
    private val secrets: ProfileSecrets,
    private val http: AiHttp = HttpUrlConnectionAiHttp()
) : AiProvider {
    suspend fun ask(prompt: String): String {
        require(prompt.isNotBlank()) { "Enter a message before sending it." }
        return complete(listOf(AiMessage("user", prompt.trim()))).content
    }

    override suspend fun complete(messages: List<AiMessage>): AiCompletion = withContext(Dispatchers.IO) {
        ProviderSupport.checkMessages(messages)
        val stored = secrets.read(profile.secretName)
        if (stored === SecretRead.Unreadable) throw AiProviderException("CREDENTIALS_UNREADABLE", CredentialStatus.UNREADABLE_MESSAGE)
        val key = (stored as? SecretRead.Present)?.value?.takeIf { it.isNotBlank() }
            ?: throw AiProviderException("MISSING_CREDENTIALS", "Add an API key in Settings before using the AI provider.")
        val endpoint = ProviderSupport.endpointOf(profile)

        try {
            val payload = JSONObject()
                .put("model", profile.model)
                .put("messages", JSONArray().apply {
                    messages.forEach { put(JSONObject().put("role", it.role).put("content", it.content)) }
                })
            if (acceptsTemperature(profile.model)) payload.put("temperature", 0.4)
            val request = AiHttpRequest(
                endpoint,
                mapOf(
                    "Authorization" to "Bearer $key",
                    "Content-Type" to "application/json; charset=utf-8",
                    "Accept" to "application/json",
                    "User-Agent" to "ONE-AI-Android"
                ),
                payload.toString()
            )
            http.open(request).use { response ->
                val raw = ProviderSupport.body(response)
                if (response.status !in 200..299) throw ProviderSupport.httpFailure(response.status, raw, profile.name, key)
                val json = ProviderSupport.json(raw)
                val choice = json.optJSONArray("choices")?.optJSONObject(0)
                    ?: throw AiProviderException("EMPTY_RESPONSE", "The provider returned no completion.")
                val message = choice.optJSONObject("message")
                    ?: throw AiProviderException("INVALID_RESPONSE", "The provider response did not contain a message.")
                val answer = extractContent(message.opt("content"))
                if (answer.isBlank()) throw AiProviderException("EMPTY_RESPONSE", "The provider returned an empty answer.")
                AiCompletion(answer, json.optString("model", profile.model), profile.name)
            }
        } catch (e: AiProviderException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: java.io.IOException) {
            throw ProviderSupport.networkFailure(e)
        } catch (e: Exception) {
            throw AiProviderException("REQUEST_FAILED", ProviderSupport.scrub(e.message ?: "The AI request failed.", key).take(250), e)
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
        private val REASONING_MODEL = Regex("o\\d.*|gpt-5.*")

        /** OpenAI's reasoning models reject a custom temperature; every other model accepts it. */
        fun acceptsTemperature(model: String): Boolean = !REASONING_MODEL.matches(model.trim().substringAfterLast('/'))
    }
}
