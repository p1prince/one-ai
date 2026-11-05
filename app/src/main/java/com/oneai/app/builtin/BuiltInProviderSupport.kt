package com.oneai.app.builtin

import com.oneai.app.bubble.BubbleServiceProvider
import com.oneai.app.plugins.PluginAdapter
import com.oneai.app.plugins.PluginAdapterRegistrar

/**
 * Registers a fresh adapter for the Bubble that is asking. The runtime is shared by the whole process, so a
 * previous registration may hold the Context of a Bubble service that has since stopped; replacing it keeps
 * the behaviour of one adapter per use that these providers had when each call built its own runtime.
 */
internal fun ensureRegistered(runtime: PluginAdapterRegistrar, pluginId: String, adapter: () -> PluginAdapter): Result<Unit> {
    runtime.unregister(pluginId)
    return runtime.register(adapter())
}

/** Runtime coupling stays inside this interim provider module, outside the core Bubble contract. */
abstract class BuiltInBubbleProvider(protected val runtime: PluginAdapterRegistrar) : BubbleServiceProvider {
    protected fun ensureRegistered(pluginId: String, adapter: () -> PluginAdapter): Result<Unit> =
        ensureRegistered(runtime, pluginId, adapter)

    override fun releaseReady() { runtime.unregister(pluginId) }
}
