package com.oneai.app.bubble

import com.oneai.app.plugins.ExternalPluginManifest
import com.oneai.app.plugins.ExternalView
import com.oneai.app.plugins.ExternalViewElement
import com.oneai.app.plugins.ExternalViewParser
import com.oneai.app.plugins.PluginActionInput

/**
 * Shows the Bubble service of a trusted, independently installed plugin. The plugin supplies only a
 * declarative view; the core renders it and every button press goes through the normal path: the plugin
 * runtime, the permission gateway and an approval panel the core draws itself, showing the exact action.
 */
class ExternalBubbleProvider(
    private val manifest: ExternalPluginManifest,
    /** Fetches the plugin's view JSON for the given service ID (null when unavailable). */
    private val viewSource: suspend (serviceId: String) -> String?
) : BubbleServiceProvider {
    private val declaration = requireNotNull(manifest.bubble) { "The plugin declares no Bubble service" }
    override val serviceId = declaration.service
    override val pluginId = manifest.id
    override val title = declaration.title
    override val isAsync = true

    override fun view(host: BubbleHost) = BubbleView(listOf(BubbleElement.Label("Loading…")))

    override suspend fun loadView(host: BubbleHost): BubbleView {
        val parsed = ExternalViewParser.parse(viewSource(serviceId), manifest).getOrThrow()
        return BubbleView(parsed.elements.map { toElement(parsed, it) })
    }

    private fun toElement(view: ExternalView, element: ExternalViewElement): BubbleElement = when (element) {
        is ExternalViewElement.Label -> BubbleElement.Label(element.text, element.size)
        is ExternalViewElement.Input -> BubbleElement.TextInput(element.key, element.hint)
        is ExternalViewElement.Button -> BubbleElement.Button(element.label) { inputs -> pressed(view, element, inputs) }
    }

    private fun pressed(view: ExternalView, button: ExternalViewElement.Button, inputs: Map<String, String>): BubbleAction? {
        val command = manifest.commands.firstOrNull { it.name == button.command } ?: return null
        val args = view.resolveArgs(button, inputs)
        return BubbleAction.PluginCall(
            pluginId = pluginId,
            input = PluginActionInput(command.name, command.capability, "Run command: ${command.title}".take(MAX_DESCRIPTION).trim(), args),
            approval = BubbleApproval(
                title = "ALLOW " + command.title.uppercase().take(60) + "?",
                message = "${manifest.name} wants to run \"${command.title}\" using the ${command.capability} capability. Only continue if you expect this.",
                review = reviewText(args),
                confirmLabel = "Allow once"
            ),
            outcomes = BubbleOutcomes("${manifest.name} unavailable", "${manifest.name} blocked", "${manifest.name} failed"),
            backTo = serviceId
        )
    }

    /** The exact values that will be sent, so a plugin cannot hide what an approval covers. */
    private fun reviewText(args: Map<String, String>): String? {
        if (args.isEmpty()) return null
        val text = args.entries.joinToString("\n") { (key, value) -> "$key: ${value.take(MAX_REVIEW_VALUE)}" }
        return text.take(MAX_REVIEW_TOTAL)
    }

    private companion object {
        const val MAX_DESCRIPTION = 300
        const val MAX_REVIEW_VALUE = 200
        const val MAX_REVIEW_TOTAL = 800
    }
}
