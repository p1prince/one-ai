package com.oneai.app.core

/**
 * Persistence-only contract for per-plugin configuration values (Stage 9.6b, decision D5).
 *
 * Kept separate from [PluginStore] so that interface does not grow. The store never judges values: callers
 * validate against the plugin's declared schema before calling [save] (see `PluginConfiguration`).
 * Values are wire strings: `true`/`false`, a decimal integer, plain text or an option value.
 */
interface PluginConfigStore {
    /** The stored values for [pluginId], or an empty map when none are stored or the stored data was unreadable. */
    fun values(pluginId: String): Map<String, String>

    /** Replaces everything stored for [pluginId]. An empty [values] map is allowed. */
    fun save(pluginId: String, schemaVersion: Int, values: Map<String, String>)

    /** Removes everything stored for [pluginId]. Removing a plugin with nothing stored is not an error. */
    fun remove(pluginId: String)
}
