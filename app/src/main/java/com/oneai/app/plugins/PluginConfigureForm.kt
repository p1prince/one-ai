package com.oneai.app.plugins

/**
 * Stage 9.6d — pure, Android-free logic behind the Plugin Centre "Configure" screen (decision D5,
 * `docs/STAGE_9_CONFIG_DESIGN.md` §7). The screen is rendered by the host from the plugin's declared schema; the
 * plugin never draws UI in ONE AI.
 *
 * A draft is a map of setting key to the text the user has typed or chosen. Toggles are always `true`/`false`.
 * Every message names the setting by its title and never echoes the value, because values may be personal.
 * Saving only changes values; it never enables a plugin, grants a capability or grants Bubble access.
 */
object PluginConfigureForm {
    /** The starting draft: each setting's effective value, or empty text when it has none (a toggle starts off). */
    fun initialDraft(schema: PluginSettingsSchema, effective: Map<String, String>): Map<String, String> =
        schema.settings.associate { declaration ->
            declaration.key to (effective[declaration.key] ?: if (declaration.type == PluginSettingType.TOGGLE) "false" else "")
        }

    /**
     * Field-level problems for [draft], keyed by setting key. Empty means the draft can be saved. A blank optional
     * number or choice means "leave as is", so it is not an error; a blank required setting is.
     */
    fun errors(schema: PluginSettingsSchema, draft: Map<String, String>): Map<String, String> {
        val problems = LinkedHashMap<String, String>()
        for (declaration in schema.settings) {
            val value = draft[declaration.key].orEmpty()
            if (isUnset(declaration, value)) {
                if (declaration.required) problems[declaration.key] = "'${declaration.title}' is required"
                continue
            }
            PluginSettingsValidator.validateValue(declaration, value).exceptionOrNull()?.let {
                problems[declaration.key] = it.message ?: "'${declaration.title}' is not valid"
            }
        }
        return problems
    }

    /**
     * The values to hand to the host on Save: every setting the user has a value for. Blank optional numbers and
     * choices are left out (use "Reset to defaults" to clear them); blank optional text is saved as empty text.
     */
    fun valuesToSave(schema: PluginSettingsSchema, draft: Map<String, String>): Map<String, String> =
        schema.settings.mapNotNull { declaration ->
            val value = draft[declaration.key].orEmpty()
            if (isUnset(declaration, value)) null else declaration.key to value
        }.toMap()

    /** Whether the draft differs from what is currently in effect, so Save can stay disabled until something changed. */
    fun hasChanges(schema: PluginSettingsSchema, effective: Map<String, String>, draft: Map<String, String>): Boolean =
        initialDraft(schema, effective) != schema.settings.associate { it.key to (draft[it.key].orEmpty()) }

    /** What the plugin card says when required settings have no value, or null when nothing is missing. */
    fun needsConfigurationNote(missingTitles: List<String>, enabled: Boolean): String? {
        if (missingTitles.isEmpty()) return null
        val what = missingTitles.joinToString()
        return if (enabled) {
            "Needs configuration: $what. It will not run until you set it."
        } else {
            "Needs configuration: $what. It cannot be turned on until you set it."
        }
    }

    /** A blank text field is a real (empty) value; a blank number or choice means no value was given. */
    private fun isUnset(declaration: PluginSettingDeclaration, value: String): Boolean = when (declaration.type) {
        PluginSettingType.TOGGLE -> false
        PluginSettingType.TEXT -> declaration.required && value.isBlank()
        PluginSettingType.NUMBER, PluginSettingType.CHOICE -> value.isBlank()
    }
}
