package com.oneai.app.ai

import com.oneai.app.security.SecretRead

/** Where profiles and the active choice are kept. The app's preferences-backed store implements it. */
interface ProviderProfileStorage {
    fun readProfiles(): String?
    fun writeProfiles(json: String)
    fun readActiveId(): String?
    fun writeActiveId(id: String)

    /** The raw values of the single pre-profile configuration, `null` where never saved. Read only to migrate. */
    fun legacySettings(): LegacyProviderSettings
}

data class LegacyProviderSettings(val endpoint: String?, val model: String?, val name: String?)

/** Keeps API keys out of the profile list. `null` selects the original primary key. */
interface ProfileSecrets {
    fun read(secretName: String?): SecretRead
    fun save(secretName: String?, value: String)
}

sealed interface ProfileOutcome {
    data class Ok(val profile: AiProviderProfile) : ProfileOutcome
    data class Refused(val message: String) : ProfileOutcome
}

/**
 * The list of saved AI service profiles and the one that is active (decision D-V / V1, slice S1).
 *
 * - First use migrates the old single endpoint/model/name into a profile with id [DEFAULT_ID] that keeps using the
 *   primary key, so nothing is re-encrypted and no key text passes through this class.
 * - A stored list that cannot be read is not overwritten by a read: the default profile is shown instead and the
 *   stored text is only replaced when the user changes a profile.
 * - Keys are never logged and never part of any returned value.
 */
class ProviderProfiles(
    private val storage: ProviderProfileStorage,
    private val secrets: ProfileSecrets,
    private val knownKinds: () -> Set<String>,
    private val log: (String) -> Unit = {}
) {
    @Synchronized fun list(): List<AiProviderProfile> = load()

    @Synchronized fun activeId(): String {
        val profiles = load()
        return storage.readActiveId()?.takeIf { id -> profiles.any { it.id == id } } ?: profiles.first().id
    }

    @Synchronized fun active(): AiProviderProfile {
        val profiles = load()
        val id = storage.readActiveId()
        return profiles.firstOrNull { it.id == id } ?: profiles.first()
    }

    @Synchronized fun get(id: String): AiProviderProfile? = load().firstOrNull { it.id == id }

    @Synchronized fun setActive(id: String): Boolean {
        val profiles = load()
        val profile = profiles.firstOrNull { it.id == id } ?: return false
        persist(profiles, profile.id)
        log("Active AI provider profile: ${profile.name}")
        return true
    }

    /** A name that no existing profile uses, for the "Add profile" button. */
    @Synchronized fun suggestName(): String {
        val used = load().map { it.name.lowercase() }.toSet()
        var n = used.size + 1
        while ("provider $n" in used) n++
        return "Provider $n"
    }

    @Synchronized fun add(
        name: String,
        endpoint: String,
        model: String,
        kind: String = DEFAULT_KIND
    ): ProfileOutcome {
        val profiles = load()
        if (profiles.size >= ProviderProfileRules.MAX_PROFILES) {
            return ProfileOutcome.Refused("You can keep up to ${ProviderProfileRules.MAX_PROFILES} provider profiles.")
        }
        if (kind !in knownKinds()) return ProfileOutcome.Refused("This version of ONE AI does not support that provider type.")
        fieldProblem(profiles, null, name, endpoint, model)?.let { return ProfileOutcome.Refused(it) }
        val id = nextId(profiles)
        val profile = AiProviderProfile(
            id = id,
            name = ProviderProfileRules.cleanName(name),
            kind = kind,
            endpoint = endpoint.trim(),
            model = model.trim(),
            secretName = ProviderProfileRules.secretNameFor(id)
        )
        persist(profiles + profile, activeIdOf(profiles))
        log("Added AI provider profile: ${profile.name}")
        return ProfileOutcome.Ok(profile)
    }

    @Synchronized fun update(id: String, name: String, endpoint: String, model: String): ProfileOutcome {
        val profiles = load()
        val current = profiles.firstOrNull { it.id == id } ?: return ProfileOutcome.Refused("That provider profile no longer exists.")
        fieldProblem(profiles, id, name, endpoint, model)?.let { return ProfileOutcome.Refused(it) }
        val changed = current.copy(
            name = ProviderProfileRules.cleanName(name),
            endpoint = endpoint.trim(),
            model = model.trim()
        )
        persist(profiles.map { if (it.id == id) changed else it }, activeIdOf(profiles))
        log("Updated AI provider profile: ${changed.name}")
        return ProfileOutcome.Ok(changed)
    }

    /** Removes a profile and its key. The last remaining profile cannot be removed. */
    @Synchronized fun remove(id: String): ProfileOutcome {
        val profiles = load()
        val target = profiles.firstOrNull { it.id == id } ?: return ProfileOutcome.Refused("That provider profile no longer exists.")
        if (profiles.size <= 1) return ProfileOutcome.Refused("Keep at least one provider profile.")
        val remaining = profiles.filterNot { it.id == id }
        val wasActive = activeIdOf(profiles) == id
        runCatching { secrets.save(target.secretName, "") }
        persist(remaining, if (wasActive) remaining.first().id else activeIdOf(profiles))
        log("Removed AI provider profile: ${target.name}")
        return ProfileOutcome.Ok(target)
    }

    @Synchronized fun saveKey(id: String, value: String): Boolean {
        val profile = load().firstOrNull { it.id == id } ?: return false
        if (value.isBlank()) return false
        secrets.save(profile.secretName, value.trim())
        log("Saved key for AI provider profile: ${profile.name}")
        return true
    }

    @Synchronized fun removeKey(id: String): Boolean {
        val profile = load().firstOrNull { it.id == id } ?: return false
        secrets.save(profile.secretName, "")
        log("Removed key for AI provider profile: ${profile.name}")
        return true
    }

    @Synchronized fun keyResult(id: String): SecretRead {
        val profile = load().firstOrNull { it.id == id } ?: return SecretRead.Absent
        return secrets.read(profile.secretName)
    }

    private fun fieldProblem(
        profiles: List<AiProviderProfile>,
        selfId: String?,
        name: String,
        endpoint: String,
        model: String
    ): String? {
        ProviderProfileRules.fieldError(name, endpoint, model)?.let { return it }
        val clean = ProviderProfileRules.cleanName(name)
        if (profiles.any { it.id != selfId && it.name.equals(clean, ignoreCase = true) }) {
            return "A provider profile with that name already exists."
        }
        return null
    }

    private fun activeIdOf(profiles: List<AiProviderProfile>): String =
        storage.readActiveId()?.takeIf { id -> profiles.any { it.id == id } } ?: profiles.first().id

    private fun nextId(profiles: List<AiProviderProfile>): String {
        val used = profiles.map { it.id }.toSet()
        var n = 1
        while ("p$n" in used) n++
        return "p$n"
    }

    private fun persist(profiles: List<AiProviderProfile>, activeId: String) {
        storage.writeProfiles(ProviderProfileCodec.encode(profiles))
        storage.writeActiveId(activeId)
    }

    private fun load(): List<AiProviderProfile> {
        val raw = storage.readProfiles()
        if (raw == null) {
            // First use: carry the single pre-profile configuration over. Nothing about the key moves.
            val migrated = listOf(migratedDefault())
            persist(migrated, DEFAULT_ID)
            return migrated
        }
        val valid = ProviderProfileCodec.decode(raw)
            ?.filter { ProviderProfileRules.isStructurallyValid(it) }
            ?.distinctBy { it.id }
            ?.take(ProviderProfileRules.MAX_PROFILES)
        // Unreadable or empty: show the default but do not overwrite what is stored.
        return if (valid.isNullOrEmpty()) listOf(migratedDefault()) else valid
    }

    private fun migratedDefault(): AiProviderProfile {
        val legacy = storage.legacySettings()
        return AiProviderProfile(
            id = DEFAULT_ID,
            name = ProviderProfileRules.cleanName(legacy.name.orEmpty()).ifEmpty { DEFAULT_NAME },
            kind = DEFAULT_KIND,
            endpoint = legacy.endpoint?.trim().orEmpty().ifEmpty { DEFAULT_ENDPOINT },
            model = legacy.model?.trim().orEmpty().ifEmpty { DEFAULT_MODEL },
            secretName = null
        )
    }

    companion object {
        const val DEFAULT_ID = "default"
        const val DEFAULT_KIND = "openai_compatible"
        const val DEFAULT_NAME = "OpenAI-compatible"
        const val DEFAULT_ENDPOINT = "https://api.openai.com/v1/chat/completions"
        const val DEFAULT_MODEL = "gpt-4o-mini"
    }
}
