package com.oneai.app.ai

import java.net.URI

/** Validation shared by Settings status and the provider request boundary. */
object ProviderSettingsPolicy {
    fun endpointError(value: String): String? {
        val clean = value.trim()
        if (clean.isEmpty()) return "Enter an HTTPS chat-completions endpoint."
        val uri = runCatching { URI(clean) }.getOrNull()
            ?: return "The provider endpoint is not a valid URL."
        if (!uri.scheme.equals("https", ignoreCase = true)) return "Provider endpoint must use HTTPS."
        if (uri.host.isNullOrBlank()) return "Provider endpoint must include a valid host."
        if (uri.userInfo != null || uri.rawFragment != null) return "Provider endpoint cannot contain embedded credentials or a fragment."
        return null
    }
}
