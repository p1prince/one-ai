package com.oneai.plugin.sdk

/**
 * The user's settings for this plugin, as last delivered by ONE AI (plugin API level 3). The host validated
 * every value against the schema in the plugin's manifest and sends the effective value, so a declared
 * setting always has a value unless it had neither a stored value nor a default. Values are plain text;
 * use the typed getters for toggles and numbers. Immutable; read it again on each command.
 */
class PluginConfig(val schemaVersion: Int, values: Map<String, String>) {
    private val values: Map<String, String> = LinkedHashMap(values)

    /** Every setting that has a value. */
    val all: Map<String, String> get() = LinkedHashMap(values)

    operator fun get(key: String): String? = values[key]

    fun string(key: String, fallback: String = ""): String = values[key] ?: fallback

    /** `toggle` settings arrive as "true" or "false". */
    fun boolean(key: String, fallback: Boolean = false): Boolean = when (values[key]) {
        "true" -> true
        "false" -> false
        else -> fallback
    }

    /** `number` settings arrive as whole numbers. */
    fun int(key: String, fallback: Int = 0): Int = values[key]?.toIntOrNull() ?: fallback

    companion object {
        /** What a plugin sees before the host has delivered anything (or on a host older than API level 3). */
        val EMPTY = PluginConfig(0, emptyMap())
    }
}

/**
 * Keeps the latest valid snapshot. A snapshot that breaks a bound is rejected whole and the previous one stays,
 * so a bad delivery can never leave a plugin half-configured. Shared by [OneAiPluginService] and the test kit
 * so both behave identically. Thread-safe.
 */
class PluginConfigHolder {
    @Volatile private var snapshot: PluginConfig = PluginConfig.EMPTY

    val current: PluginConfig get() = snapshot

    fun apply(schemaVersion: Int, keys: List<String>?, values: List<String>?): Result<PluginConfig> =
        PluginBounds.parseConfig(schemaVersion, keys, values).onSuccess { snapshot = it }
}
