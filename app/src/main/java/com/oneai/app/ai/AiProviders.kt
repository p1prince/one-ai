package com.oneai.app.ai

import com.oneai.app.core.OneAiStore
import com.oneai.app.security.SecretRead
import com.oneai.app.security.SecretVault

/** [ProfileSecrets] over the Keystore-backed vault. The only place a profile's key name meets the vault. */
class VaultProfileSecrets(private val vault: SecretVault) : ProfileSecrets {
    override fun read(secretName: String?): SecretRead =
        if (secretName == null) vault.readResult() else vault.readNamedResult(secretName)

    override fun save(secretName: String?, value: String) {
        if (secretName == null) vault.save(value) else vault.saveNamed(secretName, value)
    }
}

/** Composition of the profile list, the adapter registry and the active provider. */
object AiProviders {
    /**
     * Registers the adapters this build ships. A provider appears in Settings only if its kind is registered here, so an
     * integration is never shown as working without an adapter:
     *  - OpenAI-compatible chat completions: OpenAI (API key), DeepSeek, Groq, OpenRouter and Custom Provider;
     *  - native adapters: Google Gemini, Anthropic Claude, Ollama (local);
     *  - ChatGPT Account, only when a [chatGpt] sign-in is supplied (it needs Android storage).
     */
    fun registry(
        secrets: ProfileSecrets,
        http: AiHttp = HttpUrlConnectionAiHttp(),
        chatGpt: ChatGptAccount? = null
    ): AiProviderRegistry = AiProviderRegistry().apply {
        ProviderCatalog.OPENAI_COMPATIBLE.forEach { kind -> register(kind) { profile -> AiProviderClient(profile, secrets, http) } }
        register(ProviderCatalog.GEMINI) { profile -> GeminiProvider(profile, secrets, http) }
        register(ProviderCatalog.CLAUDE) { profile -> AnthropicProvider(profile, secrets, http) }
        register(ProviderCatalog.OLLAMA) { profile -> OllamaProvider(profile, secrets, http) }
        if (chatGpt != null) register(ProviderCatalog.CHATGPT_ACCOUNT) { profile -> ChatGptAccountProvider(profile, chatGpt, http) }
    }

    fun profiles(store: OneAiStore, vault: SecretVault, registry: AiProviderRegistry = registry(VaultProfileSecrets(vault))): ProviderProfiles =
        ProviderProfiles(store, VaultProfileSecrets(vault), registry::kinds) { store.log(it) }

    /** For callers that build their own provider (they share the same stored profiles as the app). */
    fun active(store: OneAiStore, vault: SecretVault, chatGpt: ChatGptAccount? = null): AiProvider {
        val secrets = VaultProfileSecrets(vault)
        val registry = registry(secrets, chatGpt = chatGpt)
        return ActiveAiProvider(ProviderProfiles(store, secrets, registry::kinds) { store.log(it) }, registry)
    }
}
