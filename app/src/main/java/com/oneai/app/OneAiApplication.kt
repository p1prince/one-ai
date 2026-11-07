package com.oneai.app

import android.app.Application
import com.oneai.app.builtin.BuiltInBubbleProviders
import com.oneai.app.bubble.BubbleProviders

/**
 * Composition root. Runs before any component in every process start, including when Android
 * restarts the Floating Bubble service on its own, so the Bubble's contributors are always set up.
 */
class OneAiApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val services = AppServices.get(this)
        BuiltInBubbleProviders.registerAll(BubbleProviders.registry, services.pluginRuntime)
        // Plugins that are separate apps are restored here, so the Bubble also sees them after Android restarts its service.
        services.restorePlugins()
    }
}
