package com.oneai.app.plugins

/**
 * Transitional single source of truth for the plugins that currently ship inside the app.
 * Plugin-to-Bubble bindings are derived from this list only, so no other file hard-codes a
 * plugin ID. Stage 8/9 replaces this list with externally installed plugins.
 */
object BuiltInCatalogue {
    val templates: List<PluginTemplate> = listOf(
        PluginTemplate("one.command.tools", "Command Tools", "Create and run user-defined command shortcuts.", listOf("commands")),
        PluginTemplate("one.web.research", "Web Research", "A connector slot for approved web search providers.", listOf("internet"), bubbleService = "web_research"),
        PluginTemplate("one.screen.intelligence", "Screen Intelligence", "A permission-gated screen understanding integration.", listOf("screen_capture", "internet"), bubbleService = "screen_intelligence"),
        PluginTemplate("one.voice.interface", "Voice Interface", "A voice input and spoken response integration.", listOf("microphone", "audio"), bubbleService = "voice_interface"),
        PluginTemplate("one.automation.engine", "Automation Engine", "A safe integration point for automation engines such as AutoDroid.", listOf("accessibility", "automation"), bubbleService = "automation")
    )

    /** Plugin ID -> Bubble service ID for every template that exposes a Bubble interface. */
    val bubbleBindings: Map<String, String> =
        templates.mapNotNull { t -> t.bubbleService?.let { t.id to it } }.toMap()
}
