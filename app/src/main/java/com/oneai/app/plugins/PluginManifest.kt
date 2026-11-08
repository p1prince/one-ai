package com.oneai.app.plugins

import com.oneai.app.core.CapabilityVocabulary

/**
 * Versioned, declarative plugin contract. This describes capabilities only;
 * it never contains executable code or an entry-point class to load.
 */
data class PluginManifest(
    val manifestVersion: Int = CURRENT_VERSION,
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val capabilities: List<String>,
    val dependencies: List<String> = emptyList()
) {
    companion object { const val CURRENT_VERSION = 1 }
}

object PluginManifestValidator {
    /** The closed capability vocabulary; one source of truth shared with `CapabilityPolicy`. */
    val supportedCapabilities: Set<String> = CapabilityVocabulary.all
    private val pluginId = Regex("[a-z][a-z0-9]*(\\.[a-z0-9]+)+")
    private val semanticVersion = Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(-[0-9A-Za-z.-]+)?")

    fun validate(manifest: PluginManifest): Result<PluginManifest> = runCatching {
        require(manifest.manifestVersion == PluginManifest.CURRENT_VERSION) {
            "Unsupported plugin manifest version: ${manifest.manifestVersion}"
        }
        val id = manifest.id.trim().lowercase()
        require(pluginId.matches(id)) { "Use a reverse-domain plugin ID, e.g. dev.example.notes" }
        val name = manifest.name.trim()
        require(name.isNotEmpty() && name.length <= 60) { "Plugin name must be 1–60 characters" }
        val version = manifest.version.trim()
        require(semanticVersion.matches(version)) { "Plugin version must use semantic versioning (for example 1.2.0)" }
        val description = manifest.description.trim()
        require(description.length <= 500) { "Plugin description must be 500 characters or fewer" }
        require(manifest.capabilities.size <= 12) { "A plugin may declare at most 12 capabilities" }
        val capabilities = manifest.capabilities.map { it.trim().lowercase() }.distinct()
        require(capabilities.isNotEmpty()) { "Declare at least one capability" }
        require(capabilities.all { it in supportedCapabilities }) { "One or more capabilities are not supported" }
        require(manifest.dependencies.size <= 16) { "A plugin may declare at most 16 dependencies" }
        val dependencies = manifest.dependencies.map { it.trim().lowercase() }.distinct()
        require(dependencies.size == manifest.dependencies.size) { "Duplicate plugin dependencies are not allowed" }
        require(dependencies.all { pluginId.matches(it) }) { "Every dependency must use a valid reverse-domain plugin ID" }
        require(id !in dependencies) { "A plugin cannot depend on itself" }
        manifest.copy(id=id,name=name,version=version,description=description,capabilities=capabilities,dependencies=dependencies)
    }
}
