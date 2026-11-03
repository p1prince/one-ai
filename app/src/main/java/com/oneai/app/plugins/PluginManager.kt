package com.oneai.app.plugins

import com.oneai.app.core.OneAiStore
import com.oneai.app.core.PluginRecord

data class PluginTemplate(
    val id: String, val name: String, val description: String, val capabilities: List<String>
)

class PluginManager(private val store: OneAiStore) {
    val catalogue = listOf(
        PluginTemplate("one.command.tools", "Command Tools", "Create and run user-defined command shortcuts.", listOf("commands")),
        PluginTemplate("one.web.research", "Web Research", "A connector slot for approved web search providers.", listOf("internet", "research")),
        PluginTemplate("one.screen.intelligence", "Screen Intelligence", "A permission-gated screen understanding integration.", listOf("screen_capture")),
        PluginTemplate("one.voice.interface", "Voice Interface", "A voice input and spoken response integration.", listOf("microphone", "audio")),
        PluginTemplate("one.device.actions", "Device Actions", "User-approved Android settings and device action integrations.", listOf("device_actions")),
        PluginTemplate("one.automation.engine", "Automation Engine", "A safe integration point for automation engines such as AutoDroid.", listOf("accessibility", "automation"))
    )

    fun installed() = store.plugins()
    fun install(template: PluginTemplate) {
        if (installed().any { it.id == template.id }) return
        store.savePlugin(PluginRecord(template.id, template.name, "1.0.0", template.description, template.capabilities, false))
        store.log("Installed plugin: ${template.name} (disabled by default)")
    }
    fun setEnabled(id: String, enabled: Boolean) {
        val plugin = installed().firstOrNull { it.id == id } ?: return
        store.savePlugin(plugin.copy(enabled = enabled))
        store.log("${if (enabled) "Enabled" else "Disabled"} plugin: ${plugin.name}")
    }
    fun remove(id: String) {
        val name = installed().firstOrNull { it.id == id }?.name ?: id
        store.removePlugin(id)
        store.log("Removed plugin: $name")
    }
    fun create(id: String, name: String, description: String, capabilities: List<String>): Result<PluginRecord> {
        val cleanId = id.trim().lowercase()
        if (!cleanId.matches(Regex("[a-z][a-z0-9]*(\\.[a-z0-9]+)+"))) return Result.failure(IllegalArgumentException("Use a reverse-domain plugin ID, e.g. dev.example.notes"))
        if (name.isBlank()) return Result.failure(IllegalArgumentException("Plugin name is required"))
        if (installed().any { it.id == cleanId }) return Result.failure(IllegalArgumentException("That plugin ID is already installed"))
        val allowed = setOf("commands", "internet", "screen_capture", "microphone", "audio", "device_actions", "accessibility", "automation", "files")
        if (capabilities.any { it !in allowed }) return Result.failure(IllegalArgumentException("One or more capabilities are not supported"))
        val plugin = PluginRecord(cleanId, name.trim(), "0.1.0", description.trim(), capabilities.distinct(), false, true)
        store.savePlugin(plugin)
        store.log("Created developer plugin descriptor: ${plugin.name}")
        return Result.success(plugin)
    }
}
