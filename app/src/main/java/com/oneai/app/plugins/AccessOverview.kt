package com.oneai.app.plugins

import com.oneai.app.security.CapabilityPolicy

/** One trusted plugin in the Access overview: a title and a few short, already cleaned lines. */
data class AccessRow(val title: String, val lines: List<String>)

/** What the Settings "Access overview" shows. Read-only data; building it never changes plugin state. */
data class AccessOverviewData(val rows: List<AccessRow>, val moreCount: Int) {
    val isEmpty: Boolean get() = rows.isEmpty() && moreCount == 0
}

/**
 * Stage 10.1 (owner decision D7, D-I): one read-only place that answers "which plugins can do what, and which are
 * allowed in the Bubble" for every plugin the user has trusted.
 *
 * It only restates what the host already enforces: the approval rule is [CapabilityPolicy.requiresApproval], the same
 * rule the action gateway applies. There are no standing permissions in ONE AI, so there is nothing to toggle here;
 * Enabled, Bubble access, Configure and Remove stay on the Plugins screen. Installed-and-trusted, Enabled and Bubble
 * access remain separate lines and are never merged.
 *
 * Plugin-supplied text is untrusted and is cleaned with [CatalogueView.clean]. The list is bounded to [MAX_ROWS].
 * Pure Kotlin, no Android.
 */
object AccessOverview {
    const val MAX_ROWS = 25
    private const val MAX_CAPABILITIES_SHOWN = 10
    private const val FINGERPRINT_GROUPS = 6

    /** Plain sentences shown under the list. */
    val explanation: List<String> = listOf(
        "An approval lets one action run once and expires within a minute. ONE AI keeps no permanent grants.",
        "Removing a plugin also removes its trust, settings and Bubble access.",
        "Use the Plugins screen to enable, configure or remove a plugin, and to open its Details."
    )

    const val EMPTY_TEXT = "No plugin is trusted. Plugins you trust in Plugin Centre appear here."

    fun build(
        entries: Collection<TrustEntry>,
        statuses: Map<String, ExternalStatus>,
        enabledPluginIds: Set<String>,
        bubbleAccessIds: Set<String>,
        needsConfiguration: Map<String, List<String>>
    ): AccessOverviewData {
        val sorted = entries.sortedWith(compareBy({ title(it).lowercase() }, { it.packageName }))
        val rows = sorted.take(MAX_ROWS).map { entry ->
            val id = entry.packageName
            AccessRow(
                title(entry),
                listOf(
                    "Enabled: ${if (id in enabledPluginIds) "yes" else "no"}. ${stateLine(statuses[id], needsConfiguration[id].orEmpty())}",
                    bubbleLine(entry.trustedManifest, id in bubbleAccessIds),
                    askLine(entry.trustedManifest),
                    "Signer: ${shortFingerprint(entry.certificateSha256)}"
                )
            )
        }
        return AccessOverviewData(rows, (sorted.size - rows.size).coerceAtLeast(0))
    }

    private fun title(entry: TrustEntry): String =
        CatalogueView.clean(entry.trustedManifest.name, CatalogueView.MAX_SHOWN_NAME).ifBlank { CatalogueView.clean(entry.packageName) }

    private fun stateLine(status: ExternalStatus?, needs: List<String>): String = when (status) {
        is ExternalStatus.UpdatePending -> "An update asks for more access and is waiting for your review, so it is off."
        ExternalStatus.SignatureChanged -> "Blocked: signed with a different certificate than the one you trusted."
        ExternalStatus.NotInstalled -> "The plugin app is not installed."
        ExternalStatus.Ready, null ->
            if (needs.isEmpty()) "Trusted."
            else "Trusted, but needs configuration: ${needs.joinToString { CatalogueView.clean(it, 40) }}."
    }

    private fun bubbleLine(m: ExternalPluginManifest, allowed: Boolean): String = when {
        m.bubble == null -> "Bubble: it offers no Bubble view."
        allowed -> "Bubble: allowed by you (separate from Enabled)."
        else -> "Bubble: not allowed (separate from Enabled)."
    }

    private fun askLine(m: ExternalPluginManifest): String {
        val asking = m.capabilities.filter { CapabilityPolicy.requiresApproval(it) }
        if (asking.isEmpty()) return "Asks first: it declares no capability that asks first."
        val shown = asking.take(MAX_CAPABILITIES_SHOWN).joinToString { CatalogueView.clean(it, 40) }
        val more = asking.size - MAX_CAPABILITIES_SHOWN
        return "Asks you each time: $shown" + if (more > 0) " and $more more." else "."
    }

    /** The first groups of the certificate fingerprint, enough to recognise the signer; the full value is in Details. */
    fun shortFingerprint(sha256: String): String {
        val groups = CertificateFingerprint.display(sha256).split(":")
        return groups.take(FINGERPRINT_GROUPS).joinToString(":") + if (groups.size > FINGERPRINT_GROUPS) "\u2026" else ""
    }
}
