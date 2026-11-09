package com.oneai.app.ai

import android.util.Base64
import com.oneai.app.core.OneAiStore
import com.oneai.app.security.SecretRead
import com.oneai.app.security.SecretVault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Optional OpenAI-compatible vision adapter. The configured model must support image input. */
class OpenAiCompatibleVisionProvider(
    private val store: OneAiStore,
    private val vault: SecretVault
) : AiVisionProvider {
    private val profiles = AiProviders.profiles(store, vault)

    override suspend fun analyzeImage(prompt: String, imageBytes: ByteArray, mimeType: String): AiCompletion =
        withContext(Dispatchers.IO) {
            require(prompt.isNotBlank() && prompt.length <= 4_000) { "Image analysis instructions are invalid." }
            require(imageBytes.isNotEmpty() && imageBytes.size <= MAX_IMAGE_BYTES) { "Selected image is empty or too large." }
            require(mimeType in SUPPORTED_MIME_TYPES) { "Selected image format is not supported." }
            // Follows the active AI provider profile and that profile's own key (decision D-V / V1, slice S1).
            val profile = profiles.active()
            // Image requests are built in the OpenAI chat-completions format, so only providers that speak it are used.
            if (profile.kind !in ProviderCatalog.OPENAI_COMPATIBLE) {
                throw AiProviderException(
                    "VISION_UNSUPPORTED",
                    "Image analysis is not available for ${profile.name} yet. Switch to an OpenAI-compatible provider in Settings."
                )
            }
            val key = (profiles.keyResult(profile.id) as? SecretRead.Present)?.value?.takeIf { it.isNotBlank() }
                ?: throw AiProviderException("MISSING_CREDENTIALS", "Add an API key in Settings before using image analysis.")
            val url = runCatching { URL(profile.endpoint.trim()) }.getOrElse {
                throw AiProviderException("INVALID_ENDPOINT", "The provider endpoint is not a valid URL.")
            }
            require(url.protocol.equals("https", true) && url.host.isNotBlank() &&
                url.userInfo == null && url.ref == null) {
                "Provider endpoint must be a valid HTTPS URL without embedded credentials or fragments."
            }
            val dataUrl = "data:$mimeType;base64," + Base64.encodeToString(imageBytes, Base64.NO_WRAP)
            val content = JSONArray()
                .put(JSONObject().put("type", "text").put("text", prompt))
                .put(JSONObject().put("type", "image_url").put("image_url",
                    JSONObject().put("url", dataUrl).put("detail", "auto")))
            val payload = JSONObject()
                .put("model", profile.model)
                .put("messages", JSONArray().put(JSONObject()
                    .put("role", "user").put("content", content)))
                .put("temperature", 0.2)
            val connection = url.openConnection() as? HttpURLConnection
                ?: throw AiProviderException("CONNECTION_FAILED", "Could not open the provider connection.")
            try {
                connection.instanceFollowRedirects = false
                connection.requestMethod = "POST"
                connection.connectTimeout = 20_000
                connection.readTimeout = 60_000
                connection.doOutput = true
                connection.setRequestProperty("Authorization", "Bearer $key")
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", "ONE-AI-Android")
                connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val raw = if (stream == null) "" else stream.use {
                    AiResponseBodyReader.readUtf8(it, MAX_RESPONSE_BYTES)
                        ?: throw AiProviderException(
                            "RESPONSE_TOO_LARGE", "The AI provider response exceeded ONE AI's size limit."
                        )
                }
                if (status !in 200..299) {
                    val detail = runCatching { JSONObject(raw).optJSONObject("error")?.optString("message") }
                        .getOrNull()?.takeIf { it.isNotBlank() }
                    throw AiProviderException("HTTP_$status", detail?.take(250)
                        ?: "The AI provider returned HTTP $status.")
                }
                val json = runCatching { JSONObject(raw) }.getOrElse {
                    throw AiProviderException("INVALID_RESPONSE", "The provider returned an unreadable response.")
                }
                val message = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                    ?: throw AiProviderException("EMPTY_RESPONSE", "The provider returned no image analysis.")
                val answer = when (val value = message.opt("content")) {
                    is String -> value
                    is JSONArray -> buildString {
                        for (i in 0 until value.length()) {
                            val part = value.optJSONObject(i)?.optString("text").orEmpty()
                            if (part.isNotBlank()) { if (isNotEmpty()) append("\\n"); append(part) }
                        }
                    }
                    else -> ""
                }
                if (answer.isBlank()) throw AiProviderException("EMPTY_RESPONSE", "The provider returned an empty analysis.")
                AiCompletion(answer, json.optString("model", profile.model), profile.name)
            } catch (e: AiProviderException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw AiProviderException("REQUEST_FAILED", "The image analysis request failed.", e)
            } finally {
                connection.disconnect()
            }
        }

    companion object {
        const val MAX_IMAGE_BYTES = 20 * 1024 * 1024
        private const val MAX_RESPONSE_BYTES = 1024 * 1024
        private val SUPPORTED_MIME_TYPES = setOf("image/png", "image/jpeg", "image/webp")
    }
}
