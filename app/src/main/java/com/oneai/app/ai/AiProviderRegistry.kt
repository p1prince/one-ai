package com.oneai.app.ai

/** Builds the adapter for one profile. A new kind of AI service is one registered factory, not an edit to the app. */
fun interface AiProviderFactory {
    fun create(profile: AiProviderProfile): AiProvider
}

/** Maps a profile's `kind` to the adapter that talks to that kind of service (decision D-V / V1, slice S1). */
class AiProviderRegistry {
    private val factories = LinkedHashMap<String, AiProviderFactory>()

    @Synchronized fun register(kind: String, factory: AiProviderFactory) {
        require(ProviderProfileRules.KIND.matches(kind)) { "Provider kind is invalid." }
        require(kind !in factories) { "Provider kind is already registered." }
        factories[kind] = factory
    }

    @Synchronized fun kinds(): Set<String> = factories.keys.toSet()

    /** Refuses a profile whose kind has no adapter in this build instead of guessing another one. */
    @Synchronized fun create(profile: AiProviderProfile): AiProvider =
        factories[profile.kind]?.create(profile)
            ?: throw AiProviderException(
                "UNKNOWN_PROVIDER_KIND",
                "The selected provider profile uses a service type this version of ONE AI does not support."
            )
}

/**
 * The provider the rest of the app uses. It looks up the active profile on every request, so switching the active
 * profile in Settings takes effect on the next message without rebuilding anything.
 */
class ActiveAiProvider(
    private val profiles: ProviderProfiles,
    private val registry: AiProviderRegistry
) : AiProvider {
    override suspend fun complete(messages: List<AiMessage>): AiCompletion =
        registry.create(profiles.active()).complete(messages)
}
