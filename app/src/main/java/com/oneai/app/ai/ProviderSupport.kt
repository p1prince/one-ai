package com.oneai.app.ai

import com.oneai.app.security.CredentialStatus
import com.oneai.app.security.SecretRead
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Behaviour every provider adapter shares, so errors look the same whichever service answered:
 * message checks, key reading, bounded I/O, and normalized failures. Nothing here logs, and credentials are scrubbed
 * from any text taken from a provider before it is shown.
 */
object ProviderSupport {
    const val MAX_RESPONSE_BYTES = AiHttpLimits.MAX_BODY_BYTES
    private val ALLOWED_ROLES = setOf("system", "user", "assistant")

    fun checkMessages(messages: List<AiMessage>) {
        require(messages.isNotEmpty()) { "At least one message is required." }
        require(messages.all { it.role in ALLOWED_ROLES && it.content.isNotBlank() }) {
            "Messages must have a supported role and non-empty content."
        }
    }

    /**
     * The key saved for [profile] only. An unreadable key is reported as such rather than as a missing one.
     * Returns null when none is saved and [required] is false.
     */
    fun readKey(secrets: ProfileSecrets, profile: AiProviderProfile, required: Boolean): String? {
        val stored = secrets.read(profile.secretName)
        if (stored === SecretRead.Unreadable) throw AiProviderException("CREDENTIALS_UNREADABLE", CredentialStatus.UNREADABLE_MESSAGE)
        val key = (stored as? SecretRead.Present)?.value?.takeIf { it.isNotBlank() }
        if (key == null && required) {
            throw AiProviderException("MISSING_CREDENTIALS", "Add an API key for ${profile.name} in Settings before using it.")
        }
        return key
    }

    /** The endpoint of [profile] if it satisfies the provider's rules, otherwise a non-retryable INVALID_ENDPOINT. */
    fun endpointOf(profile: AiProviderProfile): String {
        val endpoint = profile.endpoint.trim()
        ProviderCatalog.endpointError(profile.kind, endpoint)?.let { throw AiProviderException("INVALID_ENDPOINT", it) }
        return endpoint
    }

    /** Runs blocking provider I/O off the caller's thread and turns every failure into an [AiProviderException]. */
    suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (error: AiProviderException) {
            throw error
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            throw networkFailure(error)
        } catch (error: Exception) {
            throw AiProviderException("REQUEST_FAILED", error.message?.take(250) ?: "The AI request failed.", error)
        }
    }

    /**
     * An I/O failure as a normalized exception. Failing to reach the server at all is CONNECTION_FAILED; anything else
     * that goes wrong mid-request is REQUEST_FAILED. Both are retried by [RetryingAiProvider].
     */
    fun networkFailure(error: IOException): AiProviderException = when (error) {
        is ConnectException, is UnknownHostException, is SocketTimeoutException, is NoRouteToHostException ->
            AiProviderException("CONNECTION_FAILED", "Could not reach the AI provider. Check the connection and the endpoint.", error)
        else -> AiProviderException("REQUEST_FAILED", error.message?.take(250) ?: "The AI request failed.", error)
    }

    /** Reads a bounded body or fails with RESPONSE_TOO_LARGE. */
    fun body(response: AiHttpResponse): String =
        response.readBody(MAX_RESPONSE_BYTES)
            ?: throw AiProviderException("RESPONSE_TOO_LARGE", "The AI provider response exceeded the 1 MiB safety limit.")

    fun json(raw: String): JSONObject =
        runCatching { JSONObject(raw) }.getOrElse {
            throw AiProviderException("INVALID_RESPONSE", "The provider returned an unreadable response.")
        }

    /** A failed HTTP status as a normalized exception. Code is `HTTP_<status>`; the text never contains [secrets]. */
    fun httpFailure(status: Int, raw: String?, label: String, vararg secrets: String?): AiProviderException {
        val detail = errorDetail(raw)?.let { scrub(it, *secrets) }?.take(300)?.takeIf { it.isNotBlank() }
        val fallback = when (status) {
            401, 403 -> "$label rejected the credentials or their permissions (HTTP $status). Check the key and account access."
            429 -> "$label is rate limiting requests (HTTP 429). Try again shortly."
            else -> "The AI provider returned HTTP $status."
        }
        return AiProviderException("HTTP_$status", detail ?: fallback)
    }

    /** The human-readable part of the usual JSON error shapes, or null (an HTML or unknown body is never echoed). */
    fun errorDetail(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val node: Any = runCatching {
            if (text.startsWith("[")) JSONArray(text).opt(0) ?: return null else JSONObject(text)
        }.getOrNull() ?: return null
        val json = node as? JSONObject ?: return null
        val error = json.opt("error")
        val candidates = listOf(
            (error as? JSONObject)?.optString("message"),
            error as? String,
            json.optString("message"),
            json.optString("detail"),
            json.optString("error_description")
        )
        return candidates.firstOrNull { !it.isNullOrBlank() }
    }

    /** Replaces every non-blank secret in [text]. A provider that echoes a key back cannot leak it through an error. */
    fun scrub(text: String, vararg secrets: String?): String {
        var clean = text
        secrets.forEach { secret -> if (!secret.isNullOrBlank()) clean = clean.replace(secret, "[redacted]") }
        return clean
    }

    /** `base` + `suffix` with exactly one slash between them. */
    fun join(base: String, suffix: String): String = base.trimEnd('/') + "/" + suffix.trimStart('/')
}
