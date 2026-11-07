package com.oneai.app.plugins

import java.net.URI

/**
 * One catalogue entry as listed by one source (Stage 9.7d, decision D6). The same plugin id listed by two sources is
 * two rows: the list shows which source says what and never merges them. Read-only information about this device.
 */
data class AvailableEntry(
    val sourceUrl: String,
    /** Untrusted text from the source, already cleaned for display. */
    val sourceName: String,
    val entry: CatalogueEntry,
    val status: CatalogueEntryStatus
) {
    /** Stable list key: one row per source and plugin id. */
    val key: String get() = sourceUrl.lowercase() + "|" + entry.id
}

/** What the Sources dialog shows for one source. Text is already cleaned for display. */
data class SourceLine(val url: String, val title: String, val detail: String, val isError: Boolean)

/**
 * Stage 9.7d — pure, Android-free policy and formatting behind the Plugin Centre catalogue list, the entry detail and
 * the Sources dialog. Everything that comes from a source is untrusted text and passes through [clean] before it is
 * drawn. Nothing here installs, imports, trusts, enables or grants Bubble access; it only describes what a listing
 * says and how it relates to this device.
 */
object CatalogueView {
    const val MAX_SHOWN_NAME = 60
    const val MAX_SHOWN_SUMMARY = 200
    const val MAX_SHOWN_LINE = 120

    /**
     * Rows for every listed entry, in source order, each source's entries sorted by name. [facts] describes the
     * plugin apps found on the device by package name; [enabledPluginIds] are plugins that are installed and enabled.
     */
    fun available(
        listings: List<CatalogueListing>,
        facts: Map<String, InstalledPluginFacts>,
        enabledPluginIds: Set<String>
    ): List<AvailableEntry> = listings.flatMap { listing ->
        val document = listing.document ?: return@flatMap emptyList<AvailableEntry>()
        val sourceName = sourceTitle(listing)
        document.entries
            .sortedBy { it.name.lowercase() }
            .map { entry ->
                AvailableEntry(
                    sourceUrl = listing.source.url,
                    sourceName = sourceName,
                    entry = entry,
                    status = CatalogueMatcher.classify(entry, facts[entry.id], enabledPluginIds)
                )
            }
    }

    /**
     * Text from a catalogue is shown as plain text only: control characters become spaces, characters that can
     * reorder or hide text (bidirectional controls and zero-width characters) are removed, runs of spaces collapse,
     * and the result is cut to [max] characters.
     */
    fun clean(text: String, max: Int = MAX_SHOWN_LINE): String {
        val out = StringBuilder(text.length)
        for (ch in text) {
            when {
                ch in HIDDEN -> Unit
                Character.isISOControl(ch) || ch == ' ' || ch == ' ' -> out.append(' ')
                else -> out.append(ch)
            }
        }
        return out.toString().replace(Regex(" {2,}"), " ").trim().take(max)
    }

    private val HIDDEN: Set<Char> = buildSet {
        add('؜'); add('​'); add('‌'); add('‍'); add('‎'); add('‏'); add('﻿')
        for (c in '‪'..'‮') add(c)
        for (c in '⁦'..'⁩') add(c)
    }

    fun stateLabel(status: CatalogueEntryStatus): String = when (status.install) {
        CatalogueInstallState.NOT_INSTALLED -> "Not installed"
        CatalogueInstallState.INSTALLED_NOT_IMPORTED -> "Installed, not imported"
        CatalogueInstallState.IMPORTED -> "Imported"
    }

    /** Warnings and facts to show with an entry, most serious first. Empty when there is nothing to flag. */
    fun notes(status: CatalogueEntryStatus): List<String> = buildList {
        if (status.signerDiffers) {
            add("The installed app is signed with a certificate this source does not list. Importing from here is blocked.")
        }
        when (val compatibility = status.compatibility) {
            is Compatibility.Incompatible -> add(clean(compatibility.reason, 200))
            is Compatibility.CompatibleWithWarning -> add(clean(compatibility.warning, 200))
            Compatibility.Compatible -> Unit
        }
        if (status.missingDependencies.isNotEmpty()) {
            add("Needs these plugins installed and enabled: ${status.missingDependencies.joinToString()}")
        }
        if (status.updateAvailable) {
            add("A newer version is listed. Updates come through your app installer, and any extra access still needs your review.")
        }
    }

    /** One short line under an entry's name: version and capabilities. */
    fun summaryLine(entry: CatalogueEntry): String {
        val capabilities = if (entry.capabilities.isEmpty()) "" else " • ${entry.capabilities.joinToString()}"
        return clean("v${entry.versionName}$capabilities")
    }

    /** The entry's page if it is a safe https URL to open, otherwise null. ONE AI only opens it; it never downloads. */
    fun openableUrl(entry: CatalogueEntry): String? = CatalogueValidator.validateUrl(entry.getUrl).getOrNull()

    /**
     * The on-device plugin the existing fingerprint-confirmed import should review for this row, or null when the
     * catalogue view may not offer it (not installed, already imported, signer mismatch, incompatible, or the
     * installed package has no readable manifest). The import itself, and its trust checks, are unchanged.
     */
    fun importable(row: AvailableEntry, candidates: List<PluginCandidate>): DiscoveredPlugin? {
        if (!row.status.canImportFromCatalogue) return null
        return candidates.firstOrNull {
            !it.imported && it.plugin.packageName == row.entry.id && it.plugin.manifest.isSuccess
        }?.plugin
    }

    fun sourceTitle(listing: CatalogueListing): String {
        val name = listing.document?.name ?: listing.source.name
        return clean(name.orEmpty(), MAX_SHOWN_NAME).ifBlank { hostOf(listing.source.url) }
    }

    fun sourceLine(listing: CatalogueListing): SourceLine {
        val record = listing.source
        val document = listing.document
        val error = record.lastError?.let { clean(it, CatalogueSourceCodec.MAX_ERROR) }
        val listed = document?.let {
            val skipped = if (it.skippedEntries > 0) ", ${it.skippedEntries} skipped as invalid" else ""
            "${it.entries.size} listed$skipped"
        }
        val detail = when {
            error != null && listed != null -> "$error Showing the last good listing ($listed)."
            error != null -> error
            listed != null -> listed
            else -> "Not refreshed yet. Tap Refresh to load it."
        }
        return SourceLine(record.url, sourceTitle(listing), detail, isError = error != null)
    }

    /** A safe message for a failed add; the reasons come from [CatalogueValidator] and [CatalogueSources]. */
    fun problem(error: Throwable): String = clean(error.message ?: "That source could not be added", 200)

    /** The summary shown after an explicit Refresh. A null result means the refresh itself could not run. */
    fun refreshMessage(outcomes: List<SourceRefreshOutcome>?): String {
        if (outcomes == null) return "The refresh could not run."
        if (outcomes.isEmpty()) return "Add a source first."
        val failed = outcomes.count { it.error != null }
        return when {
            failed == 0 -> "Refreshed ${plural(outcomes.size)}."
            failed == outcomes.size -> "No source could be refreshed. The last good listings are kept."
            else -> "Refreshed ${outcomes.size - failed} of ${plural(outcomes.size)}; $failed failed. The last good listings are kept."
        }
    }

    private fun plural(count: Int) = if (count == 1) "1 source" else "$count sources"

    private fun hostOf(url: String): String =
        runCatching { URI(url).host }.getOrNull().orEmpty().ifBlank { "Unknown source" }
}
