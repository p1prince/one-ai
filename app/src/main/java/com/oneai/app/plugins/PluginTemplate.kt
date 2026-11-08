package com.oneai.app.plugins

/**
 * Declarative description of an installable plugin. [bubbleService] is the generic Bubble
 * service id the plugin contributes, or null when it has no Bubble interface.
 */
data class PluginTemplate(
    val id: String, val name: String, val description: String, val capabilities: List<String>,
    val version: String = "1.0.0", val dependencies: List<String> = emptyList(),
    val bubbleService: String? = null
)
