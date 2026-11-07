package com.oneai.app

import com.oneai.app.bubble.BubbleProviderRegistry
import com.oneai.app.core.PluginConfigStore
import com.oneai.app.core.PluginStore
import com.oneai.app.plugins.PluginExecutionServices
import com.oneai.app.plugins.PluginManager
import com.oneai.app.plugins.PluginTemplate

/**
 * Production composition root for the plugin host. The same composition supports an empty catalogue and
 * empty Bubble registry, making the core shell's no-plugin state a real production configuration.
 */
class PluginHostComposition(
    store: PluginStore,
    catalogue: List<PluginTemplate> = emptyList(),
    val bubbleProviders: BubbleProviderRegistry = BubbleProviderRegistry(),
    onPluginRemoved: (String) -> Unit = {},
    bubbleBindings: () -> Map<String, String> = { emptyMap() },
    configStore: PluginConfigStore? = null
) {
    val execution = PluginExecutionServices(store)
    val manager = PluginManager(
        store = store,
        catalogue = catalogue,
        onPluginRemoved = onPluginRemoved,
        bubbleBindings = bubbleBindings,
        lifecycle = execution.runtime,
        configStore = configStore
    )
}
