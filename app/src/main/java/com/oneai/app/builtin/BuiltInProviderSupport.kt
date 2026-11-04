package com.oneai.app.builtin

import com.oneai.app.bubble.BubbleServiceProvider
import com.oneai.app.plugins.PluginAdapter
import com.oneai.app.plugins.PluginRuntime

/**
 * Registers a fresh adapter for the Bubble that is asking. The runtime is shared by the whole process, so a
 * previous registration may hold the Context of a Bubble service that has since stopped; replacing it keeps
 * the behaviour of one adapter per use that these providers had when each call built its own runtime.
 */
internal fun ensureRegistered(runtime: PluginRuntime, pluginId: String, adapter: () -> PluginAdapter): Result<Unit> {
    runtime.unregister(pluginId)
    return runtime.register(adapter())
}

/** Shared by the interim in-app providers: their adapter holds the Bubble's Context, so it goes when the Bubble stops. */
abstract class BuiltInBubbleProvider : BubbleServiceProvider {
    override fun releaseReady(runtime: PluginRuntime) { runtime.unregister(pluginId) }
}
