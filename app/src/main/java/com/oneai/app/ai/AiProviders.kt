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
    /** Registers the adapters this build ships. Today: OpenAI-compatible chat completions. */
    fun registry(secrets: ProfileSecrets): AiProviderRegistry = AiProviderRegistry().apply {
        register(ProviderProfiles.DEFAULT_KIND) { profile -> AiProviderClient(profile, secrets) }
    }

    fun profiles(store: OneAiStore, vault: SecretVault, registry: AiProviderRegistry = registry(VaultProfileSecrets(vault))): ProviderProfiles =
        ProviderProfiles(store, VaultProfileSecrets(vault), registry::kinds) { store.log(it) }

    /** For callers that build their own provider (they share the same stored profiles as the app). */
    fun active(store: OneAiStore, vault: SecretVault): AiProvider {
        val secrets = VaultProfileSecrets(vault)
        val registry = registry(secrets)
        return ActiveAiProvider(ProviderProfiles(store, secrets, registry::kinds) { store.log(it) }, registry)
    }
}
