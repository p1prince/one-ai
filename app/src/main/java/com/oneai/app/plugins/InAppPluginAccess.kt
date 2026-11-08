package com.oneai.app.plugins

/** A plugin whose declared screen can be opened inside the app. Names are cleaned plugin text, bounded. */
data class InAppPluginEntry(val pluginId: String, val name: String, val screenTitle: String)

/**
 * Slice C3 (decision D12 / D-AD): which plugins may be opened inside the app.
 *
 * A plugin is listed only when ALL of these hold: the user trusts it (it has a trust entry and its status is Ready),
 * the user has turned it on (Enabled), it declares a screen, and no required setting is missing. Bubble access is
 * deliberately not an input: "Show in Floating Bubble" is a separate user decision and never changes this list.
 *
 * Pure Kotlin, no Android, so the rule is covered by JVM tests. Building the list changes nothing.
 */
object InAppPluginAccess {
    const val MAX_ENTRIES = 25

    fun build(
        entries: Collection<TrustEntry>,
        statuses: Map<String, ExternalStatus>,
        enabledPluginIds: Set<String>,
        needsConfiguration: Map<String, List<String>>
    ): List<InAppPluginEntry> = entries
        .filter { entry ->
            val id = entry.packageName
            statuses[id] == ExternalStatus.Ready &&
                id in enabledPluginIds &&
                entry.trustedManifest.bubble != null &&
                needsConfiguration[id].isNullOrEmpty()
        }
        .map { entry ->
            val manifest = entry.trustedManifest
            val name = CatalogueView.clean(manifest.name, CatalogueView.MAX_SHOWN_NAME).ifBlank { CatalogueView.clean(entry.packageName) }
            val title = CatalogueView.clean(manifest.bubble?.title.orEmpty(), CatalogueView.MAX_SHOWN_NAME).ifBlank { name }
            InAppPluginEntry(entry.packageName, name, title)
        }
        .sortedWith(compareBy({ it.name.lowercase() }, { it.pluginId }))
        .take(MAX_ENTRIES)
}
