package com.oneai.app.plugins

/**
 * Stage 9.6a — generic, capability-neutral per-plugin configuration schema (decision D5,
 * `docs/STAGE_9_CONFIG_DESIGN.md`).
 *
 * Pure Kotlin with no Android imports. A plugin declares its settings declaratively in the manifest;
 * the host validates, stores and renders them. Values are always strings on the wire and in storage:
 * `true`/`false` for toggles, a decimal integer for numbers, the text itself, or an option value.
 * Secrets are intentionally not supported in version 1 (decision D-A).
 */
enum class PluginSettingType {
    TOGGLE, TEXT, NUMBER, CHOICE;

    companion object {
        fun parse(raw: String): PluginSettingType? = entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
    }
}

data class PluginSettingOption(val value: String, val label: String)

data class PluginSettingDeclaration(
    val key: String,
    val type: PluginSettingType,
    val title: String,
    val description: String = "",
    /** Default value in wire form, or null for "no default". A toggle without a default defaults to `false`. */
    val default: String? = null,
    /** When true the setting must have a value (stored or default) before the plugin may be enabled. */
    val required: Boolean = false,
    /** TEXT only: maximum length in characters. */
    val maxLength: Int? = null,
    /** NUMBER only: inclusive bounds. */
    val min: Int? = null,
    val max: Int? = null,
    /** CHOICE only: the allowed options. */
    val options: List<PluginSettingOption> = emptyList()
)

data class PluginSettingsSchema(
    val version: Int = PluginSettingsValidator.SCHEMA_VERSION,
    val settings: List<PluginSettingDeclaration>
)

object PluginSettingsValidator {
    const val SCHEMA_VERSION = 1
    const val MAX_SETTINGS = 24
    const val MAX_TITLE = 60
    const val MAX_DESCRIPTION = 200
    const val MAX_TEXT = 500
    /** Total declared text capacity across a schema, so a full snapshot always fits [MAX_SNAPSHOT_BYTES]. */
    const val MAX_TOTAL_TEXT = 2_000
    const val MIN_OPTIONS = 2
    const val MAX_OPTIONS = 16
    const val MAX_TOTAL_OPTIONS = 96
    const val MAX_OPTION_LABEL = 40
    const val MAX_SNAPSHOT_BYTES = 8 * 1024

    private val settingKey = Regex("[a-z][a-z0-9_]{0,39}")
    private val optionValue = Regex("[a-z0-9_]{1,40}")

    /** Returns a normalised copy of the schema, or a failure whose message is safe to show the user. */
    fun validateSchema(schema: PluginSettingsSchema): Result<PluginSettingsSchema> = runCatching {
        require(schema.version == SCHEMA_VERSION) { "Unsupported settings version: ${schema.version}" }
        require(schema.settings.isNotEmpty()) { "Declare at least one setting or omit <settings>" }
        require(schema.settings.size <= MAX_SETTINGS) { "A plugin may declare at most $MAX_SETTINGS settings" }
        val normalised = schema.settings.map { normaliseDeclaration(it) }
        require(normalised.map { it.key }.distinct().size == normalised.size) { "Duplicate setting keys are not allowed" }
        require(normalised.sumOf { it.options.size } <= MAX_TOTAL_OPTIONS) {
            "A plugin may declare at most $MAX_TOTAL_OPTIONS options in total"
        }
        require(normalised.filter { it.type == PluginSettingType.TEXT }.sumOf { it.maxLength ?: MAX_TEXT } <= MAX_TOTAL_TEXT) {
            "Text settings may total at most $MAX_TOTAL_TEXT characters"
        }
        schema.copy(settings = normalised)
    }

    private fun normaliseDeclaration(declaration: PluginSettingDeclaration): PluginSettingDeclaration {
        val key = declaration.key.trim()
        require(settingKey.matches(key)) { "Setting keys use lowercase letters, digits and underscores (max 40)" }
        val title = declaration.title.trim()
        require(title.isNotEmpty() && title.length <= MAX_TITLE) { "Setting titles must be 1–$MAX_TITLE characters" }
        val description = declaration.description.trim()
        require(description.length <= MAX_DESCRIPTION) { "Setting descriptions must be $MAX_DESCRIPTION characters or fewer" }
        val base = declaration.copy(key = key, title = title, description = description)

        val typed = when (declaration.type) {
            PluginSettingType.TOGGLE -> base.copy(
                default = base.default?.trim()?.lowercase() ?: "false",
                maxLength = null, min = null, max = null, options = emptyList()
            )
            PluginSettingType.TEXT -> {
                val maxLength = declaration.maxLength ?: MAX_TEXT
                require(maxLength in 1..MAX_TEXT) { "Text settings allow 1–$MAX_TEXT characters" }
                base.copy(maxLength = maxLength, min = null, max = null, options = emptyList())
            }
            PluginSettingType.NUMBER -> {
                val low = declaration.min ?: Int.MIN_VALUE
                val high = declaration.max ?: Int.MAX_VALUE
                require(low <= high) { "A number setting's min must not exceed its max" }
                base.copy(min = low, max = high, maxLength = null, options = emptyList())
            }
            PluginSettingType.CHOICE -> {
                val options = declaration.options.map { PluginSettingOption(it.value.trim(), it.label.trim()) }
                require(options.size in MIN_OPTIONS..MAX_OPTIONS) { "A choice setting needs $MIN_OPTIONS–$MAX_OPTIONS options" }
                require(options.all { optionValue.matches(it.value) }) {
                    "Option values use lowercase letters, digits and underscores (max 40)"
                }
                require(options.all { it.label.isNotEmpty() && it.label.length <= MAX_OPTION_LABEL }) {
                    "Option labels must be 1–$MAX_OPTION_LABEL characters"
                }
                require(options.map { it.value }.distinct().size == options.size) { "Duplicate option values are not allowed" }
                base.copy(options = options, maxLength = null, min = null, max = null)
            }
        }

        val defaultValue = typed.default ?: return typed
        return typed.copy(default = validateValue(typed, defaultValue).getOrThrow())
    }

    /**
     * Validates one value against its declaration and returns its normalised wire form. Failure messages
     * name the setting but never echo the value, because values may be personal.
     */
    fun validateValue(declaration: PluginSettingDeclaration, raw: String): Result<String> = runCatching {
        when (declaration.type) {
            PluginSettingType.TOGGLE -> {
                val value = raw.trim().lowercase()
                require(value == "true" || value == "false") { "'${declaration.title}' must be on or off" }
                value
            }
            PluginSettingType.TEXT -> {
                val limit = declaration.maxLength ?: MAX_TEXT
                require(raw.length <= limit) { "'${declaration.title}' must be $limit characters or fewer" }
                require(raw.none { it.isISOControl() }) { "'${declaration.title}' must be a single line of plain text" }
                raw
            }
            PluginSettingType.NUMBER -> {
                val number = raw.trim().toIntOrNull()
                require(number != null) { "'${declaration.title}' must be a whole number" }
                val low = declaration.min ?: Int.MIN_VALUE
                val high = declaration.max ?: Int.MAX_VALUE
                require(number in low..high) { "'${declaration.title}' is out of range" }
                number.toString()
            }
            PluginSettingType.CHOICE -> {
                val value = raw.trim()
                require(declaration.options.any { it.value == value }) { "'${declaration.title}' is not one of the allowed options" }
                value
            }
        }
    }

    /**
     * Validates a set of values against a (validated) schema. Unknown keys are rejected, every value must
     * pass its declaration, and the whole snapshot must fit [MAX_SNAPSHOT_BYTES]. Nothing is truncated.
     */
    fun validateValues(schema: PluginSettingsSchema, values: Map<String, String>): Result<Map<String, String>> = runCatching {
        val byKey = schema.settings.associateBy { it.key }
        val accepted = LinkedHashMap<String, String>()
        for ((key, raw) in values) {
            val declaration = byKey[key] ?: error("Unknown setting: ${key.take(MAX_KEY_ECHO)}")
            accepted[key] = validateValue(declaration, raw).getOrThrow()
        }
        val bytes = accepted.entries.sumOf { it.key.toByteArray(Charsets.UTF_8).size + it.value.toByteArray(Charsets.UTF_8).size }
        require(bytes <= MAX_SNAPSHOT_BYTES) { "Configuration is too large" }
        accepted
    }

    /**
     * The values a plugin should see: each stored value that still validates, otherwise the declared default.
     * Settings with neither are absent. Stale or invalid stored data is ignored, never passed on.
     */
    fun effectiveValues(schema: PluginSettingsSchema, stored: Map<String, String>): Map<String, String> =
        schema.settings.mapNotNull { declaration ->
            val value = stored[declaration.key]?.let { validateValue(declaration, it).getOrNull() } ?: declaration.default
            value?.let { declaration.key to it }
        }.toMap()

    /** Keys of required settings that have no usable stored value and no default. Non-empty blocks enabling (D-C). */
    fun missingRequired(schema: PluginSettingsSchema, stored: Map<String, String>): List<String> {
        val effective = effectiveValues(schema, stored)
        return schema.settings.filter { it.required && it.key !in effective }.map { it.key }
    }

    private const val MAX_KEY_ECHO = 40
}

object PluginSettingsParser {
    private const val MAX_ATTRIBUTE_LENGTH = 1_000

    /** Maps a `<settings>` element to an (unvalidated) schema. [ExternalManifestValidator] validates it. */
    fun parse(node: XmlNode): Result<PluginSettingsSchema> = runCatching {
        require(node.name == "settings") { "Expected a <settings> element" }
        require(count(node) <= 1 + PluginSettingsValidator.MAX_SETTINGS + PluginSettingsValidator.MAX_TOTAL_OPTIONS) {
            "Settings declaration is too large"
        }
        require(flatten(node).all { n -> n.attributes.values.all { it.length <= MAX_ATTRIBUTE_LENGTH } }) {
            "Settings attribute values are too long"
        }
        val version = node.attributes["version"]?.trim()?.toIntOrNull() ?: error("Settings version is required")
        val settings = node.children.filter { it.name == "setting" }.map { setting ->
            val type = PluginSettingType.parse(setting.attributes["type"].orEmpty()) ?: error("Unsupported setting type")
            PluginSettingDeclaration(
                key = setting.attributes["key"].orEmpty(),
                type = type,
                title = setting.attributes["title"].orEmpty(),
                description = setting.attributes["description"].orEmpty(),
                default = setting.attributes["default"],
                required = setting.attributes["required"]?.trim()?.lowercase() == "true",
                maxLength = setting.wholeNumber("maxLength"),
                min = setting.wholeNumber("min"),
                max = setting.wholeNumber("max"),
                options = setting.children.filter { it.name == "option" }.map {
                    PluginSettingOption(it.attributes["value"].orEmpty(), it.attributes["label"].orEmpty())
                }
            )
        }
        PluginSettingsSchema(version = version, settings = settings)
    }

    private fun XmlNode.wholeNumber(attribute: String): Int? {
        val raw = attributes[attribute] ?: return null
        return raw.trim().toIntOrNull() ?: error("Setting attribute '$attribute' must be a whole number")
    }

    private fun count(node: XmlNode): Int = 1 + node.children.sumOf { count(it) }

    private fun flatten(node: XmlNode): List<XmlNode> = listOf(node) + node.children.flatMap { flatten(it) }
}
