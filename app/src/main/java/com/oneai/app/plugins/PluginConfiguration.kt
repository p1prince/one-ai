package com.oneai.app.plugins

import com.oneai.app.core.PluginConfigStore

/**
 * Stage 9.6b: the host-side owner of per-plugin configuration values (decision D5,
 * `docs/STAGE_9_CONFIG_DESIGN.md`). Pure logic over [PluginConfigStore] and the plugin's declared schema, so
 * it is JVM-testable.
 *
 * - Nothing is stored that does not validate against the schema; nothing is truncated.
 * - Configuration is not authority: this class never enables a plugin, grants a capability or grants Bubble access.
 * - Activity entries name the keys that changed and never the values, because values may be personal.
 *
 * [schemaOf] must return a schema only for a plugin that is currently trusted, so an untrusted, blocked or
 * unknown plugin has no configurable settings.
 */
class PluginConfiguration(
    private val store: PluginConfigStore,
    private val schemaOf: (String) -> PluginSettingsSchema?,
    private val log: (String) -> Unit = {}
) {
    /** The plugin's declared settings, or null when it has none or is not trusted. */
    fun schema(pluginId: String): PluginSettingsSchema? = schemaOf(pluginId)

    /** What the plugin should see: each stored value that still validates, otherwise the default. */
    fun effective(pluginId: String): Map<String, String> {
        val schema = schemaOf(pluginId) ?: return emptyMap()
        return PluginSettingsValidator.effectiveValues(schema, store.values(pluginId))
    }

    /**
     * Applies [changes] on top of the stored values. The whole result is validated; if anything is invalid
     * nothing is saved. Returns the effective values after the change.
     */
    fun update(pluginId: String, changes: Map<String, String>): Result<Map<String, String>> = runCatching {
        val schema = schemaOf(pluginId) ?: error("This plugin has no settings you can change")
        val before = PluginSettingsValidator.effectiveValues(schema, store.values(pluginId))
        val merged = LinkedHashMap(usableStored(schema, pluginId))
        merged.putAll(changes)
        val accepted = PluginSettingsValidator.validateValues(schema, merged).getOrThrow()
        store.save(pluginId, schema.version, accepted)
        val after = PluginSettingsValidator.effectiveValues(schema, accepted)
        val changed = schema.settings.map { it.key }.filter { before[it] != after[it] }
        if (changed.isNotEmpty()) log("Changed settings of $pluginId: ${changed.joinToString()}")
        after
    }

    /** Forgets every stored value so each setting returns to its declared default. */
    fun reset(pluginId: String): Result<Unit> = runCatching {
        schemaOf(pluginId) ?: error("This plugin has no settings you can change")
        store.remove(pluginId)
        log("Reset settings of $pluginId to their defaults")
    }

    /** Declarations of required settings that have neither a usable stored value nor a default. Non-empty blocks enabling (D-C). */
    fun missingRequired(pluginId: String): List<PluginSettingDeclaration> {
        val schema = schemaOf(pluginId) ?: return emptyList()
        val missing = PluginSettingsValidator.missingRequired(schema, store.values(pluginId)).toSet()
        return schema.settings.filter { it.key in missing }
    }

    /**
     * Brings stored values in line with a plugin's new [schema] after an accepted update: a value is kept only
     * if its key is still declared and it still validates; otherwise it is reset to the default (invalid) or
     * dropped (no longer declared). A null [schema] means the plugin no longer declares settings. Each reset
     * or drop is logged by key name only. [schema] is passed in because during an update the trusted schema
     * is not necessarily the one [schemaOf] reports.
     */
    fun reconcile(pluginId: String, schema: PluginSettingsSchema?) {
        val stored = store.values(pluginId)
        if (stored.isEmpty()) return
        if (schema == null) {
            store.remove(pluginId)
            log("Dropped settings of $pluginId: ${stored.keys.joinToString { it.take(MAX_KEY_LOGGED) }} (no longer declared)")
            return
        }
        val byKey = schema.settings.associateBy { it.key }
        val kept = LinkedHashMap<String, String>()
        val reset = mutableListOf<String>()
        val dropped = mutableListOf<String>()
        for ((key, value) in stored) {
            val declaration = byKey[key]
            if (declaration == null) {
                dropped += key.take(MAX_KEY_LOGGED)
                continue
            }
            val valid = PluginSettingsValidator.validateValue(declaration, value).getOrNull()
            if (valid == null) reset += key else kept[key] = valid
        }
        if (reset.isEmpty() && dropped.isEmpty() && kept == stored) return
        if (kept.isEmpty()) store.remove(pluginId) else store.save(pluginId, schema.version, kept)
        if (reset.isNotEmpty()) log("Reset settings of $pluginId to defaults after an update: ${reset.joinToString()}")
        if (dropped.isNotEmpty()) log("Dropped settings of $pluginId after an update: ${dropped.joinToString()} (no longer declared)")
    }

    /** Removes everything stored for the plugin; used when the plugin is removed. */
    fun remove(pluginId: String) = store.remove(pluginId)

    /** Stored values that are still declared and still valid, so one stale value never blocks an unrelated change. */
    private fun usableStored(schema: PluginSettingsSchema, pluginId: String): Map<String, String> {
        val byKey = schema.settings.associateBy { it.key }
        return store.values(pluginId).filter { (key, value) ->
            byKey[key]?.let { PluginSettingsValidator.validateValue(it, value).isSuccess } == true
        }
    }

    private companion object {
        const val MAX_KEY_LOGGED = 40
    }
}
