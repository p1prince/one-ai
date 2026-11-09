package com.oneai.app.ai

import org.json.JSONArray
import org.json.JSONObject

/**
 * One saved AI service configuration (decision D-V / V1, slice S1).
 *
 * [kind] selects the adapter from [AiProviderRegistry]. [secretName] names the vault secret that holds this profile's
 * API key; `null` means the original primary key, which is how the profile migrated from the single pre-profile
 * configuration keeps working without re-encrypting or copying anything. The key itself is never part of a profile.
 */
data class AiProviderProfile(
    val id: String,
    val name: String,
    val kind: String,
    val endpoint: String,
    val model: String,
    val secretName: String?
) {
    /** Never prints more than identity; there is no secret in a profile, but keep logs short anyway. */
    override fun toString() = "AiProviderProfile($id, $kind)"
}

/** Shared rules for profile fields. Pure Kotlin so every limit is testable on the JVM. */
object ProviderProfileRules {
    const val MAX_PROFILES = 8
    const val MAX_NAME = 40
    const val MAX_MODEL = 100
    const val MAX_ENDPOINT = 2048
    val ID = Regex("[a-z][a-z0-9]{0,15}")
    val KIND = Regex("[a-z][a-z0-9_]{0,31}")

    /** The vault secret name for a non-primary profile. It satisfies the vault's own name rule for every valid id. */
    fun secretNameFor(id: String) = "ai_$id"

    /** Removes control characters, trims and bounds a display name. */
    fun cleanName(raw: String): String = raw.filter { !it.isISOControl() }.trim().take(MAX_NAME)

    /** The first problem with the editable fields of a custom (HTTPS chat-completions) profile, or null. */
    fun fieldError(name: String, endpoint: String, model: String): String? =
        fieldError(ProviderCatalog.CUSTOM, name, endpoint, model)

    /**
     * The first problem with the editable fields of a profile of [kind], or null. Each kind has its own endpoint rule
     * (HTTPS only, local HTTP allowed for Ollama, or a fixed address that is not read from the user at all).
     * Names are compared case-insensitively by the caller.
     */
    fun fieldError(kind: String, name: String, endpoint: String, model: String): String? {
        if (cleanName(name).isEmpty()) return "Enter a profile name."
        if (ProviderCatalog.descriptorOrCustom(kind).endpointEditable) {
            ProviderCatalog.endpointError(kind, endpoint)?.let { return it }
            if (endpoint.trim().length > MAX_ENDPOINT) return "The provider endpoint is too long."
        }
        val cleanModel = model.trim()
        if (cleanModel.isEmpty()) return "Enter a model name."
        if (cleanModel.length > MAX_MODEL) return "The model name is too long."
        ProviderCatalog.modelError(kind, cleanModel)?.let { return it }
        return null
    }

    /** The endpoint to store: the user's text, or the provider's fixed address when it has one. */
    fun storedEndpoint(kind: String, entered: String): String {
        val descriptor = ProviderCatalog.descriptorOrCustom(kind)
        return if (descriptor.endpointEditable) entered.trim() else descriptor.defaultEndpoint
    }

    /** True when a stored entry is structurally usable. It does not judge the endpoint; that fails at request time. */
    fun isStructurallyValid(p: AiProviderProfile): Boolean =
        ID.matches(p.id) && KIND.matches(p.kind) && cleanName(p.name).isNotEmpty() &&
            (p.secretName == null || p.secretName == secretNameFor(p.id))
}

/** JSON form of the profile list. Unknown or damaged entries are skipped, never guessed. */
object ProviderProfileCodec {
    fun encode(profiles: List<AiProviderProfile>): String = JSONArray().apply {
        profiles.forEach {
            put(
                JSONObject()
                    .put("id", it.id).put("name", it.name).put("kind", it.kind)
                    .put("endpoint", it.endpoint).put("model", it.model)
                    .put("secret", it.secretName ?: JSONObject.NULL)
            )
        }
    }.toString()

    /** Null when [json] is not a JSON array at all; otherwise the entries that could be read. */
    fun decode(json: String): List<AiProviderProfile>? {
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return null
        val result = ArrayList<AiProviderProfile>()
        for (index in 0 until array.length()) {
            val o = array.optJSONObject(index) ?: continue
            val id = o.optString("id", "")
            val name = o.optString("name", "")
            val kind = o.optString("kind", "")
            if (id.isEmpty() || name.isEmpty() || kind.isEmpty()) continue
            result += AiProviderProfile(
                id = id,
                name = name,
                kind = kind,
                endpoint = o.optString("endpoint", "").take(ProviderProfileRules.MAX_ENDPOINT),
                model = o.optString("model", "").take(ProviderProfileRules.MAX_MODEL),
                secretName = if (o.isNull("secret")) null else o.optString("secret", "").takeIf { it.isNotEmpty() }
            )
        }
        return result
    }
}
