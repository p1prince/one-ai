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

    /**
     * Endpoint rule for local-model servers: HTTPS to any host, or plain HTTP only to this device
     * (localhost, 127.0.0.0/8, ::1). A LAN or internet address over HTTP is refused, because Android blocks cleartext
     * there and because a key or prompt would cross the network unencrypted.
     */
    fun localEndpointError(value: String): String? {
        val clean = value.trim()
        if (clean.isEmpty()) return "Enter the address of your Ollama server."
        val uri = runCatching { URI(clean) }.getOrNull()
            ?: return "The provider endpoint is not a valid URL."
        if (uri.host.isNullOrBlank()) return "Provider endpoint must include a valid host."
        if (uri.userInfo != null || uri.rawFragment != null) return "Provider endpoint cannot contain embedded credentials or a fragment."
        return when {
            uri.scheme.equals("https", ignoreCase = true) -> null
            uri.scheme.equals("http", ignoreCase = true) ->
                if (LoopbackHosts.isLoopback(uri.host)) null
                else "Plain HTTP is only allowed for this device (127.0.0.1 or localhost). Use HTTPS for any other address."
            else -> "Provider endpoint must use HTTPS, or HTTP to this device."
        }
    }
}
