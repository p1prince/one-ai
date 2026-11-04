package com.oneai.app.plugins

/** The plugin API level this build of ONE AI implements. Plugins declare which levels they need. */
object PluginApi {
    const val LEVEL = 1
    /** Oldest `targetCoreApi` still accepted without a warning. */
    const val MIN_SUPPORTED_TARGET = 1
}

data class ExternalCommandDecl(val name: String, val title: String, val description: String, val capability: String)

data class ExternalBubbleDecl(val serviceId: String, val title: String)

/**
 * Manifest v2: what an independently installed plugin APK declares. [id] is the APK package name,
 * so it cannot be chosen freely; the host supplies it from Android, never from plugin-written text.
 */
data class ExternalPluginManifest(
    val manifestVersion: Int,
    val id: String,
    val versionName: String,
    val versionCode: Long,
    val name: String,
    val description: String,
    val minCoreApi: Int,
    val targetCoreApi: Int,
    val capabilities: List<String>,
    val commands: List<ExternalCommandDecl> = emptyList(),
    val bubble: ExternalBubbleDecl? = null,
    val dependencies: List<String> = emptyList()
) {
    companion object { const val VERSION = 2 }
}

sealed interface PluginCompatibility {
    object Compatible : PluginCompatibility
    /** The plugin needs a newer ONE AI. */
    data class RequiresNewerCore(val requiredLevel: Int) : PluginCompatibility
    /** Works, but was built against an API older than the supported floor. */
    data class TargetOutdated(val targetLevel: Int) : PluginCompatibility
}

object ExternalManifestValidator {
    private val packageId = Regex("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+")
    private val commandName = Regex("[a-z][a-z0-9_]{0,39}")
    private val serviceId = Regex("[a-z][a-z0-9_]{0,39}")

    fun validate(manifest: ExternalPluginManifest): Result<ExternalPluginManifest> = runCatching {
        require(manifest.manifestVersion == ExternalPluginManifest.VERSION) {
            "Unsupported plugin manifest version: ${manifest.manifestVersion}"
        }
        val id = manifest.id.trim()
        require(id.length <= 100 && packageId.matches(id)) { "The plugin package name is not a valid ID" }
        val versionName = manifest.versionName.trim()
        require(versionName.isNotEmpty() && versionName.length <= 40) { "Plugin version name must be 1–40 characters" }
        require(manifest.versionCode >= 0) { "Plugin version code is invalid" }
        val name = manifest.name.trim()
        require(name.isNotEmpty() && name.length <= 60) { "Plugin name must be 1–60 characters" }
        val description = manifest.description.trim()
        require(description.length <= 500) { "Plugin description must be 500 characters or fewer" }
        require(manifest.minCoreApi >= 1) { "minCoreApi must be at least 1" }
        require(manifest.targetCoreApi >= manifest.minCoreApi) { "targetCoreApi cannot be lower than minCoreApi" }

        require(manifest.capabilities.size <= 12) { "A plugin may declare at most 12 capabilities" }
        val capabilities = manifest.capabilities.map { it.trim().lowercase() }.distinct()
        require(capabilities.isNotEmpty()) { "Declare at least one capability" }
        require(capabilities.all { it in PluginManifestValidator.supportedCapabilities }) { "One or more capabilities are not supported" }

        require(manifest.commands.size <= 50) { "A plugin may declare at most 50 commands" }
        val commands = manifest.commands.map { c ->
            val cn = c.name.trim()
            require(commandName.matches(cn)) { "Command names use lowercase letters, digits and underscores (max 40)" }
            val title = c.title.trim()
            require(title.isNotEmpty() && title.length <= 80) { "Command titles must be 1–80 characters" }
            val desc = c.description.trim()
            require(desc.length <= 300) { "Command descriptions must be 300 characters or fewer" }
            val capability = c.capability.trim().lowercase()
            require(capability in capabilities) { "Command '$cn' uses a capability the plugin does not declare" }
            ExternalCommandDecl(cn, title, desc, capability)
        }
        require(commands.map { it.name }.toSet().size == commands.size) { "Duplicate command names are not allowed" }

        val bubble = manifest.bubble?.let { b ->
            val sid = b.serviceId.trim()
            require(serviceId.matches(sid) && sid != "command_manager") { "The Bubble service ID is not valid" }
            val title = b.title.trim()
            require(title.isNotEmpty() && title.length <= 60) { "Bubble titles must be 1–60 characters" }
            ExternalBubbleDecl(sid, title)
        }

        require(manifest.dependencies.size <= 16) { "A plugin may declare at most 16 dependencies" }
        val dependencies = manifest.dependencies.map { it.trim() }.distinct()
        require(dependencies.size == manifest.dependencies.size) { "Duplicate plugin dependencies are not allowed" }
        require(dependencies.all { packageId.matches(it) && it.length <= 100 }) { "Every dependency must be a valid plugin ID" }
        require(id !in dependencies) { "A plugin cannot depend on itself" }

        manifest.copy(
            id = id, versionName = versionName, name = name, description = description,
            capabilities = capabilities, commands = commands, bubble = bubble, dependencies = dependencies
        )
    }

    fun compatibility(manifest: ExternalPluginManifest, coreLevel: Int = PluginApi.LEVEL): PluginCompatibility = when {
        manifest.minCoreApi > coreLevel -> PluginCompatibility.RequiresNewerCore(manifest.minCoreApi)
        manifest.targetCoreApi < PluginApi.MIN_SUPPORTED_TARGET -> PluginCompatibility.TargetOutdated(manifest.targetCoreApi)
        else -> PluginCompatibility.Compatible
    }
}

/** What an update adds. Anything non-empty means the user must approve the new declaration. */
data class DeclarationChanges(
    val addedCapabilities: Set<String>,
    val addedCommands: Set<String>,
    val bubbleAccessAdded: Boolean,
    val addedDependencies: Set<String>
) {
    val needsReconsent: Boolean
        get() = addedCapabilities.isNotEmpty() || addedCommands.isNotEmpty() || bubbleAccessAdded || addedDependencies.isNotEmpty()

    companion object {
        /** [old] is what the user approved; [updated] is the newly installed version. Both validated. */
        fun between(old: ExternalPluginManifest, updated: ExternalPluginManifest) = DeclarationChanges(
            addedCapabilities = updated.capabilities.toSet() - old.capabilities.toSet(),
            addedCommands = updated.commands.map { "${it.name}:${it.capability}" }.toSet() - old.commands.map { "${it.name}:${it.capability}" }.toSet(),
            bubbleAccessAdded = old.bubble == null && updated.bubble != null,
            addedDependencies = updated.dependencies.toSet() - old.dependencies.toSet()
        )
    }
}
