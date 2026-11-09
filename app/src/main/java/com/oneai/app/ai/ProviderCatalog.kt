package com.oneai.app.ai

import com.oneai.app.security.CredentialStatus
import java.net.URI

/** How a provider proves who the user is. */
enum class ProviderAuth {
    /** A key the user pastes; stored encrypted per profile. */
    API_KEY,
    /** A key may be saved but nothing is required (local models). */
    OPTIONAL_API_KEY,
    /** Signing in to an account in the browser; no key is pasted. */
    ACCOUNT_OAUTH
}

/** Which endpoints a provider accepts. */
enum class EndpointPolicy {
    /** HTTPS only. */
    HTTPS_ONLY,
    /** HTTPS anywhere, or plain HTTP to this device only (localhost, 127.0.0.0/8, ::1). */
    HTTPS_OR_LOOPBACK_HTTP,
    /** Not editable: the adapter always talks to one fixed address, so its token can never be sent elsewhere. */
    FIXED
}

/**
 * Everything the app needs to know about one kind of AI service. A provider is only offered when its adapter is
 * registered ([ProviderCatalog.available]); a descriptor alone never makes an integration look like it works.
 */
data class ProviderDescriptor(
    val kind: String,
    val title: String,
    val summary: String,
    val auth: ProviderAuth,
    val defaultEndpoint: String,
    val endpointPolicy: EndpointPolicy,
    val endpointLabel: String,
    val defaultModel: String,
    /** Suggestions only. Any model id the account supports can be typed. */
    val models: List<String>,
    val note: String? = null
) {
    val endpointEditable: Boolean get() = endpointPolicy != EndpointPolicy.FIXED
}

/** The providers ONE AI knows how to talk to. Pure Kotlin so every rule is testable on the JVM. */
object ProviderCatalog {
    const val OPENAI = "openai"
    const val CHATGPT_ACCOUNT = "chatgpt_account"
    const val GEMINI = "gemini"
    const val CLAUDE = "claude"
    const val DEEPSEEK = "deepseek"
    const val GROQ = "groq"
    const val OPENROUTER = "openrouter"
    const val OLLAMA = "ollama"
    /** The kind that existing profiles already use (decision D13); shown as "Custom Provider". Stored data is unchanged. */
    const val CUSTOM = ProviderProfiles.DEFAULT_KIND

    /** The ChatGPT-hosted Responses endpoint, recovered from the working Orbit-style implementation. Never editable. */
    const val CHATGPT_RESPONSES_ENDPOINT = "https://chatgpt.com/backend-api/codex/responses"

    /** Kinds served by the OpenAI-compatible chat-completions adapter (and by image analysis, which uses the same format). */
    val OPENAI_COMPATIBLE: Set<String> = setOf(CUSTOM, OPENAI, DEEPSEEK, GROQ, OPENROUTER)

    val all: List<ProviderDescriptor> = listOf(
        ProviderDescriptor(
            OPENAI, "OpenAI", "OpenAI API with your own API key.", ProviderAuth.API_KEY,
            "https://api.openai.com/v1/chat/completions", EndpointPolicy.HTTPS_ONLY, "HTTPS chat-completions endpoint",
            "gpt-4o-mini", listOf("gpt-4o-mini", "gpt-4o", "gpt-4.1", "gpt-4.1-mini", "o4-mini"),
            "This is the API-key path. Signing in with a ChatGPT account is a separate provider."
        ),
        ProviderDescriptor(
            CHATGPT_ACCOUNT, "ChatGPT Account", "Use your ChatGPT plan by signing in with your account in the browser.",
            ProviderAuth.ACCOUNT_OAUTH, CHATGPT_RESPONSES_ENDPOINT, EndpointPolicy.FIXED, "Endpoint (fixed)",
            "gpt-6-luna", listOf("gpt-6-luna"),
            "Experimental. Uses the ChatGPT-hosted Codex backend, not the documented OpenAI API, so it can change or be " +
                "restricted without notice. The default model is carried over from the earlier working implementation."
        ),
        ProviderDescriptor(
            GEMINI, "Google Gemini", "Gemini API with a Google AI Studio key.", ProviderAuth.API_KEY,
            "https://generativelanguage.googleapis.com/v1beta", EndpointPolicy.HTTPS_ONLY, "HTTPS Gemini API base",
            "gemini-2.0-flash", listOf("gemini-2.0-flash", "gemini-2.5-flash", "gemini-2.5-pro")
        ),
        ProviderDescriptor(
            CLAUDE, "Anthropic Claude", "Claude through the Anthropic Messages API.", ProviderAuth.API_KEY,
            "https://api.anthropic.com/v1/messages", EndpointPolicy.HTTPS_ONLY, "HTTPS messages endpoint",
            "claude-sonnet-5-5", listOf("claude-sonnet-5-5", "claude-haiku-5-5", "claude-opus-5-5")
        ),
        ProviderDescriptor(
            DEEPSEEK, "DeepSeek", "DeepSeek API (OpenAI-compatible).", ProviderAuth.API_KEY,
            "https://api.deepseek.com/chat/completions", EndpointPolicy.HTTPS_ONLY, "HTTPS chat-completions endpoint",
            "deepseek-chat", listOf("deepseek-chat", "deepseek-reasoner")
        ),
        ProviderDescriptor(
            GROQ, "Groq", "Groq API (OpenAI-compatible).", ProviderAuth.API_KEY,
            "https://api.groq.com/openai/v1/chat/completions", EndpointPolicy.HTTPS_ONLY, "HTTPS chat-completions endpoint",
            "llama-3.3-70b-versatile", listOf("llama-3.3-70b-versatile", "llama-3.1-8b-instant")
        ),
        ProviderDescriptor(
            OPENROUTER, "OpenRouter", "Many models through one OpenRouter key (OpenAI-compatible).", ProviderAuth.API_KEY,
            "https://openrouter.ai/api/v1/chat/completions", EndpointPolicy.HTTPS_ONLY, "HTTPS chat-completions endpoint",
            "openai/gpt-4o-mini", listOf("openai/gpt-4o-mini", "google/gemini-2.0-flash-001", "meta-llama/llama-3.3-70b-instruct")
        ),
        ProviderDescriptor(
            OLLAMA, "Ollama (local)", "Models running on this device or your own server. No key needed.",
            ProviderAuth.OPTIONAL_API_KEY, "http://127.0.0.1:11434", EndpointPolicy.HTTPS_OR_LOOPBACK_HTTP,
            "Ollama address",
            "llama3.2", listOf("llama3.2", "qwen2.5", "mistral", "gemma2"),
            "Plain HTTP is accepted only for this device (127.0.0.1 or localhost). Any other address must use HTTPS."
        ),
        ProviderDescriptor(
            CUSTOM, "Custom Provider", "Any service with an OpenAI-compatible HTTPS chat-completions API.", ProviderAuth.API_KEY,
            ProviderProfiles.DEFAULT_ENDPOINT, EndpointPolicy.HTTPS_ONLY, "HTTPS chat-completions endpoint",
            ProviderProfiles.DEFAULT_MODEL, emptyList()
        )
    )

    private val byKind = all.associateBy { it.kind }

    fun descriptor(kind: String): ProviderDescriptor? = byKind[kind]

    /** A profile whose kind is not in the catalog is treated as a custom one so it can still be edited. */
    fun descriptorOrCustom(kind: String): ProviderDescriptor = byKind[kind] ?: byKind.getValue(CUSTOM)

    /** Only providers whose adapter exists in this build. */
    fun available(registeredKinds: Set<String>): List<ProviderDescriptor> = all.filter { it.kind in registeredKinds }

    /** The first problem with [endpoint] for [kind], or null. A fixed endpoint is never read from the user. */
    fun endpointError(kind: String, endpoint: String): String? {
        val descriptor = descriptorOrCustom(kind)
        return when (descriptor.endpointPolicy) {
            EndpointPolicy.FIXED -> if (endpoint.trim() == descriptor.defaultEndpoint) null else "This provider's endpoint cannot be changed."
            EndpointPolicy.HTTPS_ONLY -> ProviderSettingsPolicy.endpointError(endpoint)
            EndpointPolicy.HTTPS_OR_LOOPBACK_HTTP -> ProviderSettingsPolicy.localEndpointError(endpoint)
        }
    }

    /** Model rules that depend on how the adapter uses the name (Gemini puts it in the URL path). */
    fun modelError(kind: String, model: String): String? {
        val clean = model.trim()
        if (clean.any { it.isISOControl() }) return "The model name contains invalid characters."
        if (kind == GEMINI && !GEMINI_MODEL.matches(clean.removePrefix("models/"))) {
            return "Use a Gemini model id such as gemini-2.0-flash (letters, digits, dots, dashes)."
        }
        return null
    }

    val GEMINI_MODEL = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,99}")
}

/** What the user sees about whether a profile can be used right now. */
enum class ProviderConnectionState(val label: String, val usable: Boolean) {
    READY("Ready", true),
    LOCAL_READY("Ready (no key needed)", true),
    CONNECTED("Connected", true),
    NEEDS_KEY("Needs an API key", false),
    NOT_CONNECTED("Not connected", false),
    CREDENTIALS_UNREADABLE("Saved credential unreadable", false),
    INVALID_SETTINGS("Check the endpoint and model", false),
    ADAPTER_UNAVAILABLE("Not available in this version", false)
}

/** Which state the ChatGPT account is in, as far as the provider list is concerned. */
enum class AccountLink { NOT_CONNECTED, CONNECTED, UNREADABLE }

object ProviderConnection {
    /** Pure so every combination is testable. [settingsIssue] is the profile's own endpoint/model problem, if any. */
    fun of(
        descriptor: ProviderDescriptor,
        adapterRegistered: Boolean,
        key: CredentialStatus,
        account: AccountLink,
        settingsIssue: String?
    ): ProviderConnectionState {
        if (!adapterRegistered) return ProviderConnectionState.ADAPTER_UNAVAILABLE
        if (settingsIssue != null) return ProviderConnectionState.INVALID_SETTINGS
        return when (descriptor.auth) {
            ProviderAuth.ACCOUNT_OAUTH -> when (account) {
                AccountLink.CONNECTED -> ProviderConnectionState.CONNECTED
                AccountLink.UNREADABLE -> ProviderConnectionState.CREDENTIALS_UNREADABLE
                AccountLink.NOT_CONNECTED -> ProviderConnectionState.NOT_CONNECTED
            }
            ProviderAuth.API_KEY -> when (key) {
                CredentialStatus.SAVED -> ProviderConnectionState.READY
                CredentialStatus.UNREADABLE -> ProviderConnectionState.CREDENTIALS_UNREADABLE
                CredentialStatus.NOT_SAVED -> ProviderConnectionState.NEEDS_KEY
            }
            ProviderAuth.OPTIONAL_API_KEY ->
                if (key == CredentialStatus.UNREADABLE) ProviderConnectionState.CREDENTIALS_UNREADABLE
                else ProviderConnectionState.LOCAL_READY
        }
    }
}

/** The connection state of one saved profile, from facts the caller reads (key status, sign-in, registered adapters). */
fun ProviderConnection.forProfile(
    profile: AiProviderProfile,
    registeredKinds: Set<String>,
    key: CredentialStatus,
    account: AccountLink
): ProviderConnectionState = of(
    ProviderCatalog.descriptorOrCustom(profile.kind),
    profile.kind in registeredKinds,
    key,
    account,
    ProviderProfileRules.fieldError(profile.kind, profile.name, profile.endpoint, profile.model)
)

/** Endpoint rules for services that run on this device. */
object LoopbackHosts {
    private val OCTET = "(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)"
    private val IPV4_LOOPBACK = Regex("127(\\.$OCTET){3}")

    fun isLoopback(host: String?): Boolean {
        val h = host?.trim()?.removePrefix("[")?.removeSuffix("]")?.lowercase() ?: return false
        return h == "localhost" || h == "::1" || h == "0:0:0:0:0:0:0:1" || IPV4_LOOPBACK.matches(h)
    }

    fun hostOf(endpoint: String): String? = runCatching { URI(endpoint.trim()).host }.getOrNull()
}
