package com.oneai.app.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Stage 9.7d: the pure policy and formatting behind the catalogue list, entry detail and Sources dialog
 * (decision D6). The screens themselves are not rendered by CI.
 */
class CatalogueViewTest {
    private val signer = "a".repeat(64)
    private val otherSigner = "b".repeat(64)

    private fun entry(
        id: String = "dev.example.notes",
        name: String = "Notes",
        versionCode: Long = 2,
        minCoreApi: Int = 1,
        targetCoreApi: Int = PluginApi.LEVEL,
        dependencies: List<String> = emptyList(),
        signers: List<String> = listOf(signer),
        getUrl: String = "https://example.com/get"
    ) = CatalogueEntry(
        id = id, name = name, summary = "Quick notes", versionName = "1.0.$versionCode", versionCode = versionCode,
        minCoreApi = minCoreApi, targetCoreApi = targetCoreApi, capabilities = listOf("files"),
        dependencies = dependencies, signerSha256 = signers, getUrl = getUrl
    )

    private fun listing(
        url: String,
        entries: List<CatalogueEntry>,
        name: String = "Source",
        error: String? = null,
        skipped: Int = 0
    ) = CatalogueListing(
        CatalogueSourceRecord(url = url, name = name, lastError = error),
        CatalogueDocument(name, entries, skipped)
    )

    private fun manifest(pkg: String = "dev.example.notes", versionCode: Long = 1) = ExternalPluginManifest(
        packageName = pkg, versionName = "1.0.$versionCode", versionCode = versionCode, name = "Notes",
        description = "Quick notes", minCoreApi = 1, targetCoreApi = PluginApi.LEVEL, capabilities = listOf("files"),
        commands = listOf(ExternalCommandDeclaration("add_note", "Add note", "Saves a note", "files"))
    )

    private fun candidate(imported: Boolean = false, pkg: String = "dev.example.notes", parsed: Result<ExternalPluginManifest> = Result.success(manifest(pkg))) =
        PluginCandidate(DiscoveredPlugin(pkg, "Notes app", "svc", parsed), imported)

    // ---- Rows ----------------------------------------------------------------------------------------------

    @Test fun theSamePluginFromTwoSourcesIsTwoRowsAndNeverMerged() {
        val rows = CatalogueView.available(
            listOf(
                listing("https://one.example/c.json", listOf(entry(versionCode = 2)), name = "One"),
                listing("https://two.example/c.json", listOf(entry(versionCode = 5)), name = "Two")
            ),
            facts = emptyMap(), enabledPluginIds = emptySet()
        )
        assertEquals(2, rows.size)
        assertEquals(listOf("One", "Two"), rows.map { it.sourceName })
        assertEquals(listOf(2L, 5L), rows.map { it.entry.versionCode })
        assertEquals("keys differ per source", 2, rows.map { it.key }.distinct().size)
    }

    @Test fun entriesAreSortedByNameWithinASourceAndSourceOrderIsKept() {
        val rows = CatalogueView.available(
            listOf(
                listing("https://b.example/c.json", listOf(entry("dev.z", "zeta"), entry("dev.a", "Alpha"))),
                listing("https://a.example/c.json", listOf(entry("dev.m", "Mid")))
            ),
            emptyMap(), emptySet()
        )
        assertEquals(listOf("Alpha", "zeta", "Mid"), rows.map { it.entry.name })
    }

    @Test fun aSourceWithoutAGoodDocumentContributesNoRows() {
        val noDocument = CatalogueListing(CatalogueSourceRecord("https://x.example/c.json"), null)
        assertTrue(CatalogueView.available(listOf(noDocument), emptyMap(), emptySet()).isEmpty())
    }

    @Test fun rowsAreClassifiedAgainstTheDevice() {
        val facts = mapOf("dev.example.notes" to InstalledPluginFacts(versionCode = 1, signerSha256 = listOf(signer), imported = false))
        val rows = CatalogueView.available(listOf(listing("https://x.example/c.json", listOf(entry(versionCode = 2)))), facts, emptySet())
        val status = rows.single().status
        assertEquals(CatalogueInstallState.INSTALLED_NOT_IMPORTED, status.install)
        assertTrue(status.updateAvailable)
        assertFalse(status.signerDiffers)
    }

    // ---- Labels and notes ----------------------------------------------------------------------------------

    @Test fun everyInstallStateHasItsOwnLabelAndNoStateMeansEnabled() {
        val labels = CatalogueInstallState.values().map { state ->
            CatalogueView.stateLabel(
                CatalogueEntryStatus(state, Compatibility.Compatible, emptyList(), signerDiffers = false, updateAvailable = false)
            )
        }
        assertEquals(labels.distinct().size, labels.size)
        assertTrue(labels.none { it.contains("nable", ignoreCase = true) })
    }

    @Test fun aPlainCompatibleListingHasNoNotes() {
        val status = CatalogueEntryStatus(CatalogueInstallState.NOT_INSTALLED, Compatibility.Compatible, emptyList(), false, false)
        assertTrue(CatalogueView.notes(status).isEmpty())
    }

    @Test fun notesReportSignerMismatchIncompatibilityMissingDependenciesAndUpdatesMostSeriousFirst() {
        val facts = mapOf("dev.example.notes" to InstalledPluginFacts(1, listOf(otherSigner), imported = false))
        val rows = CatalogueView.available(
            listOf(listing("https://x.example/c.json", listOf(entry(versionCode = 2, minCoreApi = PluginApi.LEVEL + 1, targetCoreApi = PluginApi.LEVEL + 1, dependencies = listOf("dev.example.base"))))),
            facts, emptySet()
        )
        val notes = CatalogueView.notes(rows.single().status)
        assertEquals(4, notes.size)
        assertTrue(notes[0].contains("certificate"))
        assertTrue("incompatibility reason follows", notes[1].isNotBlank())
        assertTrue(notes[2].contains("dev.example.base"))
        assertTrue(notes[3].contains("newer version"))
    }

    @Test fun anEnabledDependencyIsNotReportedMissing() {
        val rows = CatalogueView.available(
            listOf(listing("https://x.example/c.json", listOf(entry(dependencies = listOf("dev.example.base"))))),
            emptyMap(), enabledPluginIds = setOf("dev.example.base")
        )
        assertTrue(rows.single().status.missingDependencies.isEmpty())
    }

    // ---- Untrusted text ------------------------------------------------------------------------------------

    @Test fun cleanRemovesBidiAndZeroWidthControlsAndTurnsControlCharactersIntoSpaces() {
        val tricky = "Safe‮elif.exe​⁦ hidden⁩\nnext\tline\u0007end"
        val cleaned = CatalogueView.clean(tricky)
        assertFalse(cleaned.any { it in '‪'..'‮' || it in '⁦'..'⁩' || it == '​' })
        assertFalse(cleaned.any { Character.isISOControl(it) })
        assertEquals("Safeelif.exe hidden next line end", cleaned)
    }

    @Test fun cleanCollapsesSpacesTrimsAndTruncates() {
        assertEquals("a b", CatalogueView.clean("   a     b   "))
        assertEquals(5, CatalogueView.clean("x".repeat(100), max = 5).length)
        assertEquals("", CatalogueView.clean("‮​"))
    }

    @Test fun aBlankSourceNameFallsBackToTheHost() {
        val record = CatalogueSourceRecord("https://catalogue.example.org/list.json", name = "‮ ")
        val title = CatalogueView.sourceTitle(CatalogueListing(record, null))
        assertEquals("catalogue.example.org", title)
    }

    // ---- Get and import ------------------------------------------------------------------------------------

    @Test fun onlyAnHttpsPageCanBeOpened() {
        assertEquals("https://example.com/get", CatalogueView.openableUrl(entry()))
        assertNull(CatalogueView.openableUrl(entry(getUrl = "http://example.com/get")))
        assertNull(CatalogueView.openableUrl(entry(getUrl = "intent://x#Intent;end")))
        assertNull(CatalogueView.openableUrl(entry(getUrl = "https://user:pw@example.com/get")))
    }

    private fun rowFor(facts: InstalledPluginFacts?, entry: CatalogueEntry = entry()) = CatalogueView.available(
        listOf(listing("https://x.example/c.json", listOf(entry))),
        facts?.let { mapOf(entry.id to it) }.orEmpty(), emptySet()
    ).single()

    @Test fun importIsOfferedOnlyForAnInstalledNotImportedCompatibleMatchingPlugin() {
        val row = rowFor(InstalledPluginFacts(1, listOf(signer), imported = false))
        val offered = CatalogueView.importable(row, listOf(candidate()))
        assertNotNull(offered)
        assertEquals("dev.example.notes", offered!!.packageName)
    }

    @Test fun importIsNotOfferedWhenTheSignerDiffers() {
        val row = rowFor(InstalledPluginFacts(1, listOf(otherSigner), imported = false))
        assertNull(CatalogueView.importable(row, listOf(candidate())))
    }

    @Test fun importIsNotOfferedWhenIncompatible() {
        val row = rowFor(
            InstalledPluginFacts(1, listOf(signer), imported = false),
            entry(minCoreApi = PluginApi.LEVEL + 1, targetCoreApi = PluginApi.LEVEL + 1)
        )
        assertNull(CatalogueView.importable(row, listOf(candidate())))
    }

    @Test fun importIsNotOfferedWhenNotInstalledOrAlreadyImported() {
        assertNull(CatalogueView.importable(rowFor(null), listOf(candidate())))
        val imported = rowFor(InstalledPluginFacts(1, listOf(signer), imported = true))
        assertNull(CatalogueView.importable(imported, listOf(candidate(imported = true))))
    }

    @Test fun importIsNotOfferedWithoutAMatchingReadableCandidate() {
        val row = rowFor(InstalledPluginFacts(1, listOf(signer), imported = false))
        assertNull("no candidate at all", CatalogueView.importable(row, emptyList()))
        assertNull("different package", CatalogueView.importable(row, listOf(candidate(pkg = "dev.example.other"))))
        assertNull("unreadable manifest", CatalogueView.importable(row, listOf(candidate(parsed = Result.failure(IllegalStateException("bad"))))))
    }

    // ---- Sources dialog and messages -----------------------------------------------------------------------

    @Test fun aNeverRefreshedSourceTellsTheUserToRefresh() {
        val line = CatalogueView.sourceLine(CatalogueListing(CatalogueSourceRecord("https://x.example/c.json"), null))
        assertFalse(line.isError)
        assertTrue(line.detail.contains("Refresh"))
    }

    @Test fun aGoodSourceReportsCountsAndSkippedEntries() {
        val line = CatalogueView.sourceLine(listing("https://x.example/c.json", listOf(entry()), skipped = 2))
        assertFalse(line.isError)
        assertEquals("Source", line.title)
        assertTrue(line.detail.contains("1 listed"))
        assertTrue(line.detail.contains("2 skipped"))
    }

    @Test fun aFailedRefreshShowsTheErrorAndKeepsTheLastGoodListing() {
        val line = CatalogueView.sourceLine(listing("https://x.example/c.json", listOf(entry()), error = "Could not reach the source"))
        assertTrue(line.isError)
        assertTrue(line.detail.contains("Could not reach the source"))
        assertTrue(line.detail.contains("last good listing"))
    }

    @Test fun aFailedRefreshWithNothingCachedShowsOnlyTheError() {
        val record = CatalogueSourceRecord("https://x.example/c.json", lastError = "Could not reach the source")
        val line = CatalogueView.sourceLine(CatalogueListing(record, null))
        assertTrue(line.isError)
        assertEquals("Could not reach the source", line.detail)
    }

    @Test fun refreshMessagesCoverEveryOutcome() {
        fun ok(url: String) = SourceRefreshOutcome(url, null, 1, 0)
        fun bad(url: String) = SourceRefreshOutcome(url, "Could not reach the source")
        assertEquals("The refresh could not run.", CatalogueView.refreshMessage(null))
        assertEquals("Add a source first.", CatalogueView.refreshMessage(emptyList()))
        assertEquals("Refreshed 1 source.", CatalogueView.refreshMessage(listOf(ok("a"))))
        assertEquals("Refreshed 2 sources.", CatalogueView.refreshMessage(listOf(ok("a"), ok("b"))))
        assertTrue(CatalogueView.refreshMessage(listOf(bad("a"), bad("b"))).startsWith("No source could be refreshed"))
        assertTrue(CatalogueView.refreshMessage(listOf(ok("a"), bad("b"))).startsWith("Refreshed 1 of 2 sources; 1 failed"))
    }

    @Test fun addProblemsAreCleanedAndNeverEmpty() {
        assertEquals("Only https URLs are allowed", CatalogueView.problem(IllegalArgumentException("Only https URLs are allowed")))
        assertEquals("That source could not be added", CatalogueView.problem(IllegalStateException()))
        assertFalse(CatalogueView.problem(IllegalStateException("bad‮\nthing")).contains('\n'))
    }

    // ---- Production guards ---------------------------------------------------------------------------------

    private fun source(relativePath: String): String =
        listOf(File("src/main/java/$relativePath"), File("app/src/main/java/$relativePath")).first { it.isFile }.readText()

    @Test fun theViewLayerIsAndroidFreeAndNeverTouchesTrustOrLifecycle() {
        val view = source("com/oneai/app/plugins/CatalogueView.kt")
        assertFalse(view.contains("import android"))
        for (forbidden in listOf("PluginTrustRegistry", "ExternalPluginRegistrar", "setEnabled", "setBubbleAccess", "import(")) {
            assertFalse("CatalogueView must not use $forbidden", view.contains(forbidden))
        }
    }

    @Test fun theScreenRefreshesOnlyAfterConsumingTheOneShotGate() {
        val screen = source("com/oneai/app/MainActivity.kt")
        val calls = Regex("refreshAll\\(").findAll(screen).count()
        assertEquals("exactly one refresh call site", 1, calls)
        assertTrue(
            "the refresh call is guarded by the gate",
            Regex("refreshGate\\.consume\\(\\)\\)[^\\n]*refreshAll\\(").containsMatchIn(screen)
        )
    }

    @Test fun theScreenOpensListedPagesOnlyThroughTheValidatedUrl() {
        val screen = source("com/oneai/app/MainActivity.kt")
        assertTrue(screen.contains("CatalogueView.openableUrl("))
        assertFalse("no raw entry URL goes to an intent", screen.contains("Uri.parse(row.entry.getUrl"))
    }
}
