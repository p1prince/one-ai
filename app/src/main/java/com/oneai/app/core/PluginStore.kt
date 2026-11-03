package com.oneai.app.core

import com.oneai.app.plugins.PluginRuntime
import com.oneai.app.security.PluginActionGateway

/**
 * The persistence and runtime surface the plugin manager needs. Keeping it as an interface lets
 * the manager run, and be tested, without Android storage (including with zero plugins).
 */
interface PluginStore {
    fun plugins(): List<PluginRecord>
    fun savePlugin(plugin: PluginRecord)
    fun removePlugin(id: String)
    fun bubbleServices(): Set<String>
    fun setBubbleServices(services: Set<String>)
    fun developerMode(): Boolean
    fun log(message: String)
    fun pluginRuntime(): PluginRuntime
    fun pluginActionGateway(): PluginActionGateway
}
