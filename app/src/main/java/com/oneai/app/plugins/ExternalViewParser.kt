package com.oneai.app.plugins

import org.json.JSONArray
import org.json.JSONObject

/** Where a button argument comes from: a fixed value, or the text typed into an input. */
sealed interface ViewArg {
    data class Literal(val value: String) : ViewArg
    data class FromInput(val key: String) : ViewArg
}

sealed interface ExternalViewElement {
    data class Label(val text: String, val size: Float) : ExternalViewElement
    data class Input(val key: String, val hint: String) : ExternalViewElement
    data class Button(val label: String, val command: String, val args: Map<String, ViewArg>) : ExternalViewElement
}

data class ExternalView(val elements: List<ExternalViewElement>) {
    /** Final argument map for a button press. Missing inputs become empty text; values stay bounded. */
    fun resolveArgs(button: ExternalViewElement.Button, inputs: Map<String, String>): Map<String, String> =
        button.args.mapValues { (_, source) ->
            when (source) {
                is ViewArg.Literal -> source.value
                is ViewArg.FromInput -> inputs[source.key].orEmpty().take(ExternalViewParser.MAX_ARG_VALUE)
            }
        }
}

/**
 * Parses the declarative Bubble view a plugin returns. The format (version 1):
 *
 *     {"v":1,"elements":[
 *       {"t":"label","text":"Hello","size":14},
 *       {"t":"input","key":"text","hint":"Type here"},
 *       {"t":"button","label":"Save","command":"add_note","args":{"text":"@text","mode":"quick"}}
 *     ]}
 *
 * An argument value starting with "@" refers to the input with that key; "@@" starts a literal "@".
 * Parsing is strict and fails closed: any unknown type, undeclared command, unknown input reference or
 * out-of-bounds value rejects the whole view, so a plugin can never show something the host did not understand.
 */
object ExternalViewParser {
    const val MAX_JSON = 20_000
    const val MAX_ELEMENTS = 40
    const val MAX_TEXT = 2_000
    const val MAX_HINT = 100
    const val MAX_BUTTON_LABEL = 60
    const val MAX_ARGS = 32
    const val MAX_ARG_KEY = 80
    const val MAX_ARG_VALUE = 4_000
    private const val DEFAULT_SIZE = 14f
    private val inputKey = Regex("[a-z][a-z0-9_]{0,31}")
    private val argKey = Regex("[A-Za-z0-9_.-]{1,80}")

    fun parse(json: String?, manifest: ExternalPluginManifest): Result<ExternalView> = runCatching {
        require(json != null && json.isNotBlank()) { "The plugin returned no view" }
        require(json.length <= MAX_JSON) { "The plugin view is too large" }
        val root = JSONObject(json)
        require(root.optInt("v", -1) == 1) { "Unsupported plugin view version" }
        val array = root.optJSONArray("elements") ?: throw IllegalArgumentException("The plugin view has no elements")
        require(array.length() <= MAX_ELEMENTS) { "The plugin view has too many elements" }
        val declared = manifest.commands.map { it.name }.toSet()
        val inputKeys = collectInputKeys(array)
        val elements = (0 until array.length()).map { index ->
            val item = array.optJSONObject(index) ?: throw IllegalArgumentException("Element ${index + 1} is not an object")
            when (val type = item.optString("t")) {
                "label" -> ExternalViewElement.Label(text(item, "text", MAX_TEXT), size(item))
                "input" -> ExternalViewElement.Input(item.optString("key"), text(item, "hint", MAX_HINT, allowBlank = true))
                "button" -> button(item, declared, inputKeys)
                else -> throw IllegalArgumentException("Unknown element type '${type.take(20)}'")
            }
        }
        ExternalView(elements)
    }

    private fun collectInputKeys(array: JSONArray): Set<String> {
        val keys = linkedSetOf<String>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            if (item.optString("t") != "input") continue
            val key = item.optString("key")
            require(inputKey.matches(key)) { "Input keys use lowercase letters, digits and underscores" }
            require(keys.add(key)) { "Duplicate input key '$key'" }
        }
        return keys
    }

    private fun text(item: JSONObject, name: String, max: Int, allowBlank: Boolean = false): String {
        val value = item.optString(name, "").trim()
        require(allowBlank || value.isNotEmpty()) { "A view element is missing its $name" }
        require(value.length <= max) { "A view element's $name is too long" }
        return value
    }

    private fun size(item: JSONObject): Float {
        val raw = item.optDouble("size", DEFAULT_SIZE.toDouble()).toFloat()
        return if (raw in 10f..24f) raw else DEFAULT_SIZE
    }

    private fun button(item: JSONObject, declared: Set<String>, inputKeys: Set<String>): ExternalViewElement.Button {
        val label = text(item, "label", MAX_BUTTON_LABEL)
        val command = item.optString("command")
        require(command in declared) { "A button uses a command the plugin did not declare" }
        val args = linkedMapOf<String, ViewArg>()
        item.optJSONObject("args")?.let { obj ->
            require(obj.length() <= MAX_ARGS) { "A button has too many arguments" }
            obj.keys().forEach { key ->
                require(argKey.matches(key)) { "An argument name is not valid" }
                val value = obj.opt(key) as? String ?: throw IllegalArgumentException("Argument '$key' must be text")
                require(value.length <= MAX_ARG_VALUE) { "Argument '$key' is too long" }
                args[key] = when {
                    value.startsWith("@@") -> ViewArg.Literal(value.substring(1))
                    value.startsWith("@") -> {
                        val ref = value.substring(1)
                        require(ref in inputKeys) { "Argument '$key' refers to an input that does not exist" }
                        ViewArg.FromInput(ref)
                    }
                    else -> ViewArg.Literal(value)
                }
            }
        }
        return ExternalViewElement.Button(label, command, args)
    }
}
