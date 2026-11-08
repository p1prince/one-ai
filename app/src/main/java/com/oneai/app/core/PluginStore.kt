package com.oneai.app.core

/**
 * Persistence-only plugin contract. Runtime registration, execution and approvals are owned by the
 * process composition root and injected through separate interfaces.
 */
interface PluginStore {
    fun plugins(): List<PluginRecord>
    fun savePlugin(plugin: PluginRecord)
    fun removePlugin(id: String)
    fun bubbleServices(): Set<String>
    fun setBubbleServices(services: Set<String>)
    fun developerMode(): Boolean
    fun log(message: String)
    /** Typed Activity entry (slice 10.2). Stores that keep no kinds just record the message. */
    fun log(message: String, kind: ActivityKind, plugin: String?) = log(message)
}
