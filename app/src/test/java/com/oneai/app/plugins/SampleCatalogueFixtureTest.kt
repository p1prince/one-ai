package com.oneai.app.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 9.7e: the sample catalogue document shipped as a test fixture (`catalogue/sample_catalogue.json`) is the
 * document the developer guide tells publishers to copy. It must parse cleanly through the real [CatalogueParser]
 * and classify as documented. Pure JVM; no network, no storage, no capability plugin.
 */
class SampleCatalogueFixtureTest {
    private val notesSigner = "ab".repeat(32)
    private val otherSigner = "99".repeat(32)

    private fun fixtureText(): String =
        requireNotNull(javaClass.classLoader?.getResourceAsStream("catalogue/sample_catalogue.json")) {
            "catalogue/sample_catalogue.json is missing from the test resources"
        }.use { String(it.readBytes(), Charsets.UTF_8) }

    private fun document(): CatalogueDocument = CatalogueParser.parse(fixtureText()).getOrThrow()

    private fun entry(id: String): CatalogueEntry = requireNotNull(document().entries.firstOrNull { it.id == id }) { "no entry $id" }

    @Test fun fixtureParsesWithoutSkippedEntries() {
        val doc = document()
        assertEquals("Example plugin source", doc.name)
        assertEquals(0, doc.skippedEntries)
        assertEquals(
            listOf("com.example.notes", "com.example.notes.extras", "com.example.future"),
            doc.entries.map { it.id }
        )
    }

    @Test fun fixtureIsAsciiOnlyAndWithinTheDocumentBound() {
        val text = fixtureText()
        assertTrue("fixture must be plain ASCII (no invisible or bidirectional characters)", text.all { it.code in 0..127 })
        assertTrue(text.toByteArray(Charsets.UTF_8).size <= CatalogueValidator.MAX_DOCUMENT_BYTES)
    }

    @Test fun everyListedUrlIsAnAcceptedHttpsUrl() {
        document().entries.forEach { e ->
            assertTrue("getUrl of ${e.id}", CatalogueValidator.validateUrl(e.getUrl).isSuccess)
            e.homepage?.let { assertTrue("homepage of ${e.id}", CatalogueValidator.validateUrl(it).isSuccess) }
        }
    }

    @Test fun colonSeparatedUppercaseFingerprintIsNormalised() {
        assertEquals(listOf(notesSigner), entry("com.example.notes").signerSha256)
    }

    @Test fun unknownFieldsAreIgnoredAndOptionalFieldsDefault() {
        val extras = entry("com.example.notes.extras")
        assertEquals(listOf("cd".repeat(32), "ef".repeat(32)), extras.signerSha256)
        assertNull(extras.homepage)
        val future = entry("com.example.future")
        assertEquals("", future.summary)
        assertTrue(future.capabilities.isEmpty())
        assertTrue(future.dependencies.isEmpty())
    }

    @Test fun dependencyIsReportedUntilTheRequiredPluginIsInstalledAndEnabled() {
        val extras = entry("com.example.notes.extras")
        val installed = InstalledPluginFacts(versionCode = 5, signerSha256 = listOf("cd".repeat(32)), imported = false)
        assertEquals(
            listOf("com.example.notes"),
            CatalogueMatcher.classify(extras, installed, enabledPluginIds = emptySet()).missingDependencies
        )
        assertTrue(
            CatalogueMatcher.classify(extras, installed, enabledPluginIds = setOf("com.example.notes")).missingDependencies.isEmpty()
        )
    }

    @Test fun notInstalledEntryOffersNoImport() {
        val status = CatalogueMatcher.classify(entry("com.example.notes"), installed = null, enabledPluginIds = emptySet())
        assertEquals(CatalogueInstallState.NOT_INSTALLED, status.install)
        assertTrue(status.compatibility.usable)
        assertFalse(status.signerDiffers)
        assertFalse(status.updateAvailable)
        assertFalse(status.canImportFromCatalogue)
    }

    @Test fun installedEntryWithMatchingSignerMayStartTheExistingImport() {
        val installed = InstalledPluginFacts(versionCode = 12, signerSha256 = listOf(notesSigner), imported = false)
        val status = CatalogueMatcher.classify(entry("com.example.notes"), installed, enabledPluginIds = emptySet())
        assertEquals(CatalogueInstallState.INSTALLED_NOT_IMPORTED, status.install)
        assertFalse(status.updateAvailable)
        assertTrue(status.canImportFromCatalogue)
    }

    @Test fun signerMismatchBlocksImportFromTheCatalogueView() {
        val installed = InstalledPluginFacts(versionCode = 12, signerSha256 = listOf(otherSigner), imported = false)
        val status = CatalogueMatcher.classify(entry("com.example.notes"), installed, enabledPluginIds = emptySet())
        assertTrue(status.signerDiffers)
        assertFalse(status.canImportFromCatalogue)
    }

    @Test fun listingNeverReportsImportedUnlessTheDeviceSaysSo() {
        val installed = InstalledPluginFacts(versionCode = 12, signerSha256 = listOf(notesSigner), imported = false)
        document().entries.forEach { e ->
            val status = CatalogueMatcher.classify(e, installed, enabledPluginIds = setOf(e.id))
            assertTrue("${e.id} must not be reported as imported", status.install != CatalogueInstallState.IMPORTED)
        }
    }

    @Test fun newerListedVersionIsInformationalOnly() {
        val installed = InstalledPluginFacts(versionCode = 11, signerSha256 = listOf(notesSigner), imported = true)
        val status = CatalogueMatcher.classify(entry("com.example.notes"), installed, enabledPluginIds = emptySet())
        assertEquals(CatalogueInstallState.IMPORTED, status.install)
        assertTrue(status.updateAvailable)
        assertFalse("an imported plugin is not offered a second import", status.canImportFromCatalogue)
    }

    @Test fun entryNeedingANewerHostIsIncompatibleAndNeverImportable() {
        val future = entry("com.example.future")
        assertTrue(future.minCoreApi > PluginApi.LEVEL)
        val installed = InstalledPluginFacts(versionCode = 20, signerSha256 = listOf("12".repeat(32)), imported = false)
        val status = CatalogueMatcher.classify(future, installed, enabledPluginIds = emptySet())
        assertFalse(status.compatibility.usable)
        assertFalse(status.canImportFromCatalogue)
    }
}
