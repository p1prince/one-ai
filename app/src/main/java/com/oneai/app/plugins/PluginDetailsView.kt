package com.oneai.app.plugins

import com.oneai.app.security.CapabilityPolicy

/** One headed block of lines in the Details dialog. */
data class DetailSection(val heading: String, val lines: List<String>)

/** Everything the Details dialog for one trusted plugin shows. Read-only; building it never changes plugin state. */
data class PluginDetails(val title: String, val sections: List<DetailSection>)

/**
 * Stage 9.8: the generic "Details" view of a plugin the user has trusted. It answers, in one place, the questions the
 * Plugin Centre must make understandable: who is this (identity), what did I trust (pinned certificate), is it usable
 * (lifecycle status, compatibility, dependencies), what can it ask for (declared capabilities and which of them always
 * prompt), which commands it registers, and whether it may appear in the Floating Bubble.
 *
 * It is built from the declaration the user approved ([TrustEntry.trustedManifest]), which is the one the host runs.
 * A pending update's declaration is shown only through the existing Review update flow. Installed, trusted, enabled
 * and Bubble access stay four separate lines; nothing here is conflated or changed.
 *
 * Plugin-supplied text is untrusted and is cleaned with [CatalogueView.clean] before display. Pure Kotlin, no Android.
 */
object PluginDetailsView {
    const val MAX_COMMANDS_SHOWN = 50

    fun build(
        entry: TrustEntry,
        status: ExternalStatus?,
        enabled: Boolean,
        bubbleAccess: Boolean,
        needsConfiguration: List<String>,
        enabledPluginIds: Set<String>,
        hostLevel: Int = PluginApi.LEVEL
    ): PluginDetails {
        val manifest = entry.trustedManifest
        return PluginDetails(
            title = text(manifest.name, CatalogueView.MAX_SHOWN_NAME).ifBlank { text(manifest.packageName) },
            sections = listOf(
                identity(manifest),
                trust(entry),
                lifecycle(status, enabled, needsConfiguration),
                compatibility(manifest, hostLevel),
                dependencies(manifest, enabledPluginIds),
                access(manifest),
                commands(manifest),
                bubble(manifest, enabled, bubbleAccess)
            )
        )
    }

    private fun text(value: String, max: Int = CatalogueView.MAX_SHOWN_LINE) = CatalogueView.clean(value, max)

    private fun identity(m: ExternalPluginManifest) = DetailSection(
        "Identity",
        buildList {
            add("Package: ${text(m.packageName)}")
            add("Version: ${text(m.versionName, 40)} (code ${m.versionCode})")
            if (m.description.isNotBlank()) add(text(m.description, CatalogueView.MAX_SHOWN_SUMMARY))
        }
    )

    private fun trust(entry: TrustEntry) = DetailSection(
        "Trusted certificate",
        listOf(
            "You trusted the app signed with this SHA-256 certificate. It is re-checked on every call; a different certificate is blocked.",
            CertificateFingerprint.display(entry.certificateSha256)
        )
    )

    private fun lifecycle(status: ExternalStatus?, enabled: Boolean, needsConfiguration: List<String>) = DetailSection(
        "Status",
        buildList {
            add(if (enabled) "Enabled by you." else "Disabled. Nothing of it runs.")
            when (status) {
                is ExternalStatus.UpdatePending -> {
                    add("An update asks for more access, so the plugin is off until you review it.")
                    status.reasons.forEach { add("- ${text(it, CatalogueView.MAX_SHOWN_SUMMARY)}") }
                }
                ExternalStatus.SignatureChanged -> add("Blocked: the installed app is signed with a different certificate than the one you trusted.")
                ExternalStatus.NotInstalled -> add("The plugin app is not installed on this device.")
                ExternalStatus.Ready, null -> if (enabled) {
                    if (needsConfiguration.isEmpty()) add("Trusted and ready.")
                    else add("Needs configuration before it runs: ${needsConfiguration.joinToString { text(it, 40) }}.")
                }
            }
        }
    )

    private fun compatibility(m: ExternalPluginManifest, hostLevel: Int) = DetailSection(
        "Compatibility",
        buildList {
            add("Needs plugin API ${m.minCoreApi}, built for ${m.targetCoreApi}. This ONE AI provides $hostLevel.")
            add(
                when (val result = PluginCompatibility.check(m, hostLevel)) {
                    Compatibility.Compatible -> "Compatible with this ONE AI."
                    is Compatibility.CompatibleWithWarning -> text(result.warning, CatalogueView.MAX_SHOWN_SUMMARY)
                    is Compatibility.Incompatible -> text(result.reason, CatalogueView.MAX_SHOWN_SUMMARY)
                }
            )
        }
    )

    private fun dependencies(m: ExternalPluginManifest, enabledPluginIds: Set<String>) = DetailSection(
        "Depends on",
        if (m.dependencies.isEmpty()) listOf("No other plugins.")
        else m.dependencies.map { id ->
            "${text(id)}: " + if (id in enabledPluginIds) "installed and enabled" else "not installed and enabled"
        }
    )

    private fun access(m: ExternalPluginManifest) = DetailSection(
        "Access it can ask for",
        if (m.capabilities.isEmpty()) listOf("None declared.")
        else m.capabilities.map { capability ->
            "${text(capability, 40)}: " +
                if (CapabilityPolicy.requiresApproval(capability)) "asks you each time, and each approval is single-use"
                else "no extra prompt"
        }
    )

    private fun commands(m: ExternalPluginManifest): DetailSection {
        if (m.commands.isEmpty()) return DetailSection("Commands", listOf("None."))
        val shown = m.commands.take(MAX_COMMANDS_SHOWN).map { c ->
            "${text(c.name, 40)} (${text(c.title, CatalogueView.MAX_SHOWN_NAME)}), uses ${text(c.capability, 40)}"
        }
        val more = m.commands.size - shown.size
        return DetailSection("Commands", if (more > 0) shown + "and $more more" else shown)
    }

    private fun bubble(m: ExternalPluginManifest, enabled: Boolean, bubbleAccess: Boolean): DetailSection {
        val declared = m.bubble ?: return DetailSection("Floating Bubble", listOf("It does not offer a Bubble view."))
        return DetailSection(
            "Floating Bubble",
            listOf(
                "It offers a Bubble view named \"${text(declared.title, CatalogueView.MAX_SHOWN_NAME)}\".",
                when {
                    bubbleAccess && enabled -> "You allowed it in the Bubble."
                    bubbleAccess -> "You allowed it in the Bubble, but it is disabled, so nothing appears."
                    else -> "Not allowed in the Bubble. This is separate from Enabled and is off until you allow it."
                }
            )
        )
    }
}
