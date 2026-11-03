package com.oneai.app.plugins

import com.oneai.app.core.OneAiStore
import com.oneai.app.core.PluginRecord

data class PluginTemplate(
    val id: String, val name: String, val description: String, val capabilities: List<String>,
    val version: String = "1.0.0", val dependencies: List<String> = emptyList()
)

class PluginManager(private val store: OneAiStore) {
    val catalogue = listOf(
        PluginTemplate("one.command.tools", "Command Tools", "Create and run user-defined command shortcuts.", listOf("commands")),
        PluginTemplate("one.web.research", "Web Research", "A connector slot for approved web search providers.", listOf("internet")),
        PluginTemplate("one.screen.intelligence", "Screen Intelligence", "A permission-gated screen understanding integration.", listOf("screen_capture")),
        PluginTemplate("one.voice.interface", "Voice Interface", "A voice input and spoken response integration.", listOf("microphone", "audio")),
        PluginTemplate("one.device.actions", "Device Actions", "User-approved Android settings and device action integrations.", listOf("device_actions")),
        PluginTemplate("one.automation.engine", "Automation Engine", "A safe integration point for automation engines such as AutoDroid.", listOf("accessibility", "automation"))
    )

    fun installed() = store.plugins()

    fun install(template: PluginTemplate): Result<PluginRecord> {
        val validated = PluginManifestValidator.validate(
            PluginManifest(id=template.id,name=template.name,version=template.version,
                description=template.description,capabilities=template.capabilities,dependencies=template.dependencies)
        ).getOrElse { return Result.failure(it) }
        if (installed().any { it.id == validated.id }) return Result.failure(IllegalArgumentException("Plugin is already installed"))
        val missing = validated.dependencies.filterNot { dependency -> installed().any { it.id == dependency } }
        if (missing.isNotEmpty()) return Result.failure(IllegalArgumentException("Missing dependencies: ${missing.joinToString()}"))
        val record = PluginRecord(validated.id,validated.name,validated.version,validated.description,
            validated.capabilities,false,false,validated.manifestVersion,validated.dependencies)
        store.savePlugin(record)
        store.log("Installed plugin: ${record.name} (disabled by default)")
        return Result.success(record)
    }

    fun setEnabled(id: String, enabled: Boolean): Result<Unit> {
        val plugin = installed().firstOrNull { it.id == id }
            ?: return Result.failure(IllegalArgumentException("Plugin is not installed"))
        if (enabled) {
            val missing = plugin.dependencies.filterNot { dependency -> installed().any { it.id == dependency && it.enabled } }
            if (missing.isNotEmpty()) return Result.failure(IllegalStateException("Enable required dependencies first: ${missing.joinToString()}"))
        } else {
            val dependents = installed().filter { it.enabled && id in it.dependencies }
            if (dependents.isNotEmpty()) return Result.failure(IllegalStateException("Disable dependent plugins first: ${dependents.joinToString { it.name }}"))
        }
        store.savePlugin(plugin.copy(enabled=enabled))
        if (!enabled) {
            store.pluginActionGateway().revokePendingApprovals()
            serviceFor(plugin.id)?.let { service -> store.setBubbleServices(store.bubbleServices() - service) }
        }
        store.log("${if (enabled) "Enabled" else "Disabled"} plugin: ${plugin.name}")
        return Result.success(Unit)
    }

    fun remove(id: String): Result<Unit> {
        val plugin = installed().firstOrNull { it.id == id }
            ?: return Result.failure(IllegalArgumentException("Plugin is not installed"))
        val dependents = installed().filter { id in it.dependencies }
        if (dependents.isNotEmpty()) return Result.failure(IllegalStateException("Remove dependent plugins first: ${dependents.joinToString { it.name }}"))
        store.pluginActionGateway().revokePendingApprovals()
        serviceFor(id)?.let { service -> store.setBubbleServices(store.bubbleServices() - service) }
        store.removePlugin(id)
        store.log("Removed plugin: ${plugin.name}")
        return Result.success(Unit)
    }

    private fun serviceFor(id: String): String? = when (id) {
        "one.web.research" -> "web_research"
        "one.screen.intelligence" -> "screen_intelligence"
        "one.voice.interface" -> "voice_interface"
        "one.automation.engine" -> "automation"
        else -> null
    }

    fun create(id: String, name: String, description: String, capabilities: List<String>,
               version: String = "0.1.0", dependencies: List<String> = emptyList()): Result<PluginRecord> {
        if (!store.developerMode()) return Result.failure(IllegalStateException("Enable Developer Mode first"))
        val validated = PluginManifestValidator.validate(
            PluginManifest(id=id,name=name,version=version,description=description,
                capabilities=capabilities,dependencies=dependencies)
        ).getOrElse { return Result.failure(it) }
        if (installed().any { it.id == validated.id }) return Result.failure(IllegalArgumentException("That plugin ID is already installed"))
        val missing = validated.dependencies.filterNot { dependency -> installed().any { it.id == dependency } }
        if (missing.isNotEmpty()) return Result.failure(IllegalArgumentException("Missing dependencies: ${missing.joinToString()}"))
        val plugin = PluginRecord(validated.id,validated.name,validated.version,validated.description,
            validated.capabilities,false,true,validated.manifestVersion,validated.dependencies)
        store.savePlugin(plugin)
        store.log("Created developer plugin manifest: ${plugin.name}")
        return Result.success(plugin)
    }
}
