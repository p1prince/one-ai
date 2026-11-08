package com.oneai.app.plugins

/**
 * Stage 9.10: what one explicit "Check updates" run found. Pure data built by [ExternalPluginCenter.checkForUpdates];
 * it changes nothing and is never stored by Core (the Activity log keeps the one-line summary).
 *
 * [checked] counts imported (trusted) plugins found on the device. [notFound] are imported plugins that were not found.
 * Names are plugin-controlled text, so they are cleaned and shortened whenever they are shown.
 */
data class UpdateCheckReport(
    val checkedAtMillis: Long,
    val checked: Int,
    val unchanged: Int,
    val updated: Int,
    /** Updates that ask for more powers and wait for the user, including ones that were already waiting. */
    val needsApproval: List<String> = emptyList(),
    /** Updates the trust rules refused (for example a downgrade); the plugin was turned off. */
    val rejected: List<String> = emptyList(),
    /** Imported plugins found on the device whose manifest could not be read. */
    val unreadable: List<String> = emptyList(),
    val notFound: List<String> = emptyList()
) {
    private val total: Int get() = checked + notFound.size

    /** One sentence, for the Plugins screen and the Activity log. */
    fun summary(): String {
        if (total == 0) return "No imported plugins to check."
        val parts = mutableListOf<String>()
        if (updated > 0) parts += "$updated updated"
        if (needsApproval.isNotEmpty()) parts += "${needsApproval.size} ${if (needsApproval.size == 1) "needs" else "need"} your approval"
        if (rejected.isNotEmpty()) parts += "${rejected.size} refused"
        if (unreadable.isNotEmpty()) parts += "${unreadable.size} could not be read"
        if (notFound.isNotEmpty()) parts += "${notFound.size} no longer installed"
        if (unchanged > 0) parts += "$unchanged unchanged"
        val head = if (total == 1) "Checked 1 plugin" else "Checked $total plugins"
        return if (parts.isEmpty()) "$head." else "$head: ${parts.joinToString(", ")}."
    }

    /** The summary followed by one line per group that needs attention. */
    fun lines(): List<String> = buildList {
        add(summary())
        group("Needs your approval", needsApproval)
        group("Refused and turned off", rejected)
        group("Could not be read", unreadable)
        group("No longer installed", notFound)
    }

    private fun MutableList<String>.group(title: String, names: List<String>) {
        if (names.isEmpty()) return
        val shown = names.take(MAX_NAMES).joinToString(", ") { CatalogueView.clean(it, MAX_NAME_LENGTH) }
        val more = names.size - MAX_NAMES
        add(if (more > 0) "$title: $shown and $more more" else "$title: $shown")
    }

    companion object {
        const val MAX_NAMES = 5
        const val MAX_NAME_LENGTH = 60
    }
}
