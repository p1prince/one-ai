package com.oneai.app.plugins

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stage 9.7a: catalogue model, parsing, bounds and entry classification. Pure JVM; no network, no storage. */
class PluginCatalogueTest {
    private val signerA = "ab".repeat(32)
    private val signerB = "cd".repeat(32)

    private fun entryJson(id: String = "com.example.notes", block: JSONObject.() -> Unit = {}): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", "Notes")
        put("summary", "Takes notes")
        put("versionName", "1.2.0")
        put("versionCode", 12)
        put("minCoreApi", 2)
        put("targetCoreApi", 3)
        put("capabilities", JSONArray(listOf("commands")))
        put("dependencies", JSONArray())
        put("signerSha256", JSONArray(listOf(signerA)))
        put("getUrl", "https://example.com/get")
        put("homepage", "https://example.com")
        block()
    }

    private fun documentJson(vararg entries: JSONObject, block: JSONObject.() -> Unit = {}): String = JSONObject().apply {
        put("catalogueVersion", 1)
        put("name", "Example source")
        put("entries", JSONArray(entries.toList()))
        block()
    }.toString()

    private fun entry(
        id: String = "com.example.notes", versionCode: Long = 12, minCoreApi: Int = 2, targetCoreApi: Int = 3,
        dependencies: List<String> = emptyList(), signers: List<String> = listOf(signerA)
    ) = CatalogueEntry(
        id = id, name = "Notes", versionName = "1.2.0", versionCode = versionCode, minCoreApi = minCoreApi,
        targetCoreApi = targetCoreApi, dependencies = dependencies, signerSha256 = signers, getUrl = "https://example.com/get"
    )

    // ---- URL rules -------------------------------------------------------------------------------------------

    @Test fun httpsUrlIsAccepted() {
        assertTrue(CatalogueValidator.validateUrl(" https://example.com/catalogue.json ").isSuccess)
    }

    @Test fun nonHttpsOrUnsafeUrlsAreRefused() {
        listOf(
            "http://example.com/a", "ftp://example.com/a", "file:///sdcard/a", "javascript:alert(1)", "intent://x#Intent;end",
            "https://user:pass@example.com/a", "https:///nohost", "https://", "example.com/a", "", "   ",
            "https://exa mple.com/a", "https://example.com/" + "a".repeat(CatalogueValidator.MAX_URL)
        ).forEach { assertTrue("should refuse: $it", CatalogueValidator.validateUrl(it).isFailure) }
    }

    // ---- Document-level acceptance and rejection --------------------------------------------------------------

    @Test fun validDocumentParses() {
        val doc = CatalogueParser.parse(documentJson(entryJson())).getOrThrow()
        assertEquals("Example source", doc.name)
        assertEquals(0, doc.skippedEntries)
        val e = doc.entries.single()
        assertEquals("com.example.notes", e.id)
        assertEquals(12L, e.versionCode)
        assertEquals(listOf(signerA), e.signerSha256)
        assertEquals("https://example.com", e.homepage)
    }

    @Test fun emptyEntryListIsAValidEmptyCatalogue() {
        val doc = CatalogueParser.parse(documentJson()).getOrThrow()
        assertTrue(doc.entries.isEmpty())
        assertEquals(0, doc.skippedEntries)
    }

    @Test fun unknownFieldsAreIgnored() {
        val doc = CatalogueParser.parse(documentJson(entryJson { put("futureField", "x") }) { put("extra", 1) }).getOrThrow()
        assertEquals(1, doc.entries.size)
    }

    @Test fun notJsonIsRejected() {
        assertTrue(CatalogueParser.parse("not json").isFailure)
        assertTrue(CatalogueParser.parse("").isFailure)
        assertTrue(CatalogueParser.parse("[]").isFailure)
    }

    @Test fun wrongOrMissingVersionIsRejected() {
        assertTrue(CatalogueParser.parse(documentJson { put("catalogueVersion", 2) }).isFailure)
        assertTrue(CatalogueParser.parse(documentJson { put("catalogueVersion", "1") }).isFailure)
        assertTrue(CatalogueParser.parse(documentJson { remove("catalogueVersion") }).isFailure)
    }

    @Test fun missingOrBadDocumentNameIsRejected() {
        assertTrue(CatalogueParser.parse(documentJson { remove("name") }).isFailure)
        assertTrue(CatalogueParser.parse(documentJson { put("name", "   ") }).isFailure)
        assertTrue(CatalogueParser.parse(documentJson { put("name", "n".repeat(CatalogueValidator.MAX_DOCUMENT_NAME + 1)) }).isFailure)
    }

    @Test fun missingOrWrongTypedEntriesListIsRejected() {
        assertTrue(CatalogueParser.parse(documentJson { remove("entries") }).isFailure)
        assertTrue(CatalogueParser.parse(documentJson { put("entries", "nope") }).isFailure)
    }

    @Test fun tooManyEntriesRejectsTheWholeDocument() {
        val entries = (0..CatalogueValidator.MAX_ENTRIES).map { entryJson("com.example.p$it") }.toTypedArray()
        assertTrue(CatalogueParser.parse(documentJson(*entries)).isFailure)
    }

    @Test fun exactlyTheMaximumNumberOfEntriesIsAccepted() {
        val entries = (1..CatalogueValidator.MAX_ENTRIES).map { entryJson("com.example.p$it") }.toTypedArray()
        assertEquals(CatalogueValidator.MAX_ENTRIES, CatalogueParser.parse(documentJson(*entries)).getOrThrow().entries.size)
    }

    @Test fun oversizeDocumentIsRejectedNotTruncated() {
        val big = documentJson(entryJson { put("summary", "s") }) { put("padding", "x".repeat(CatalogueValidator.MAX_DOCUMENT_BYTES)) }
        assertTrue(CatalogueParser.parse(big).isFailure)
    }

    @Test fun sizeLimitCountsBytesNotCharacters() {
        // 2-byte characters (not escaped by the JSON writer): under the limit in characters, over it in bytes.
        val chars = 200_000
        val big = documentJson { put("padding", "é".repeat(chars)) }
        assertTrue(chars < CatalogueValidator.MAX_DOCUMENT_BYTES)
        assertTrue(CatalogueParser.parse(big).isFailure)
    }

    @Test fun duplicatePluginIdsRejectTheWholeDocument() {
        assertTrue(CatalogueParser.parse(documentJson(entryJson(), entryJson())).isFailure)
    }

    // ---- Per-entry skipping ----------------------------------------------------------------------------------

    @Test fun oneInvalidEntryIsSkippedAndCountedWithoutLosingTheRest() {
        val bad = entryJson("com.example.bad") { put("getUrl", "http://example.com/insecure") }
        val doc = CatalogueParser.parse(documentJson(entryJson("com.example.one"), bad, entryJson("com.example.two"))).getOrThrow()
        assertEquals(listOf("com.example.one", "com.example.two"), doc.entries.map { it.id })
        assertEquals(1, doc.skippedEntries)
    }

    @Test fun nonObjectEntriesAreSkipped() {
        val text = JSONObject().put("catalogueVersion", 1).put("name", "S")
            .put("entries", JSONArray().put("string").put(5).put(JSONObject.NULL).put(entryJson())).toString()
        val doc = CatalogueParser.parse(text).getOrThrow()
        assertEquals(1, doc.entries.size)
        assertEquals(3, doc.skippedEntries)
    }

    @Test fun duplicateIdsAmongInvalidEntriesDoNotRejectTheDocument() {
        val bad1 = entryJson("com.example.same") { remove("getUrl") }
        val bad2 = entryJson("com.example.same") { remove("getUrl") }
        val doc = CatalogueParser.parse(documentJson(bad1, bad2, entryJson())).getOrThrow()
        assertEquals(1, doc.entries.size)
        assertEquals(2, doc.skippedEntries)
    }

    @Test fun everyRequiredFieldIsRequired() {
        listOf("id", "name", "versionName", "versionCode", "minCoreApi", "targetCoreApi", "signerSha256", "getUrl").forEach { key ->
            val doc = CatalogueParser.parse(documentJson(entryJson { remove(key) })).getOrThrow()
            assertTrue("entry without $key should be skipped", doc.entries.isEmpty())
            assertEquals(1, doc.skippedEntries)
        }
    }

    @Test fun optionalFieldsMayBeOmittedOrNull() {
        val doc = CatalogueParser.parse(documentJson(entryJson {
            remove("summary"); remove("capabilities"); remove("dependencies"); put("homepage", JSONObject.NULL)
        })).getOrThrow()
        val e = doc.entries.single()
        assertEquals("", e.summary)
        assertTrue(e.capabilities.isEmpty())
        assertTrue(e.dependencies.isEmpty())
        assertNull(e.homepage)
    }

    @Test fun wrongTypesMakeAnEntryInvalid() {
        listOf<JSONObject.() -> Unit>(
            { put("versionCode", "12") }, { put("versionCode", 12.5) }, { put("minCoreApi", "2") },
            { put("name", 5) }, { put("id", true) }, { put("getUrl", 7) }, { put("summary", 3) },
            { put("signerSha256", signerA) }, { put("signerSha256", JSONArray().put(5)) },
            { put("capabilities", "commands") }, { put("dependencies", JSONArray().put(1)) }, { put("homepage", 4) },
            { put("minCoreApi", 3_000_000_000L) }
        ).forEachIndexed { index, change ->
            val doc = CatalogueParser.parse(documentJson(entryJson(block = change))).getOrThrow()
            assertTrue("case $index should be skipped", doc.entries.isEmpty())
            assertEquals(1, doc.skippedEntries)
        }
    }

    // ---- Entry rules -----------------------------------------------------------------------------------------

    @Test fun entryIsNormalised() {
        val checked = CatalogueValidator.validateEntry(
            entry().copy(
                id = " com.example.notes ", name = "  Notes  ", capabilities = listOf(" Commands ", "commands"),
                signerSha256 = listOf(signerA.uppercase().chunked(2).joinToString(":"), signerA)
            )
        ).getOrThrow()
        assertEquals("com.example.notes", checked.id)
        assertEquals("Notes", checked.name)
        assertEquals(listOf("commands"), checked.capabilities)
        assertEquals(listOf(signerA), checked.signerSha256)
    }

    @Test fun invalidEntriesAreRefused() {
        val base = entry()
        listOf(
            base.copy(id = "Not.Valid"), base.copy(id = "single"), base.copy(id = "a." + "b".repeat(120)),
            base.copy(name = " "), base.copy(name = "n".repeat(CatalogueValidator.MAX_NAME + 1)),
            base.copy(summary = "s".repeat(CatalogueValidator.MAX_SUMMARY + 1)),
            base.copy(versionName = "1.2"), base.copy(versionName = "1.2.0-" + "x".repeat(CatalogueValidator.MAX_VERSION_NAME)),
            base.copy(versionCode = 0), base.copy(versionCode = -4),
            base.copy(minCoreApi = 0), base.copy(minCoreApi = 3, targetCoreApi = 2),
            base.copy(capabilities = listOf("Bad Name")), base.copy(capabilities = (1..13).map { "cap$it" }),
            base.copy(dependencies = listOf("not valid")), base.copy(dependencies = listOf("com.example.x", "com.example.x")),
            base.copy(dependencies = listOf("com.example.notes")), base.copy(dependencies = (1..17).map { "com.example.d$it" }),
            base.copy(signerSha256 = emptyList()), base.copy(signerSha256 = listOf("nothex")),
            base.copy(signerSha256 = listOf("ab".repeat(31))), base.copy(signerSha256 = (1..5).map { "%064x".format(it) }),
            base.copy(getUrl = "http://example.com"), base.copy(homepage = "http://example.com")
        ).forEachIndexed { index, bad -> assertTrue("case $index should fail", CatalogueValidator.validateEntry(bad).isFailure) }
    }

    @Test fun boundaryValuesAreAccepted() {
        val ok = entry().copy(
            name = "n".repeat(CatalogueValidator.MAX_NAME), summary = "s".repeat(CatalogueValidator.MAX_SUMMARY),
            capabilities = (1..CatalogueValidator.MAX_CAPABILITIES).map { "cap$it" },
            dependencies = (1..CatalogueValidator.MAX_DEPENDENCIES).map { "com.example.d$it" },
            signerSha256 = (1..CatalogueValidator.MAX_SIGNERS).map { "%064x".format(it) }
        )
        assertTrue(CatalogueValidator.validateEntry(ok).isSuccess)
    }

    // ---- Classification --------------------------------------------------------------------------------------

    private fun facts(versionCode: Long = 12, signers: List<String> = listOf(signerA), imported: Boolean = false) =
        InstalledPluginFacts(versionCode, signers, imported)

    @Test fun entryNotOnTheDeviceIsNotInstalled() {
        val s = CatalogueMatcher.classify(entry(), null, emptySet())
        assertEquals(CatalogueInstallState.NOT_INSTALLED, s.install)
        assertFalse(s.signerDiffers)
        assertFalse(s.updateAvailable)
        assertFalse(s.canImportFromCatalogue)
    }

    @Test fun installedButNotImportedCanStartTheExistingImport() {
        val s = CatalogueMatcher.classify(entry(), facts(), emptySet())
        assertEquals(CatalogueInstallState.INSTALLED_NOT_IMPORTED, s.install)
        assertTrue(s.canImportFromCatalogue)
    }

    @Test fun importedPluginIsReportedImportedAndNotOfferedForImportAgain() {
        val s = CatalogueMatcher.classify(entry(), facts(imported = true), emptySet())
        assertEquals(CatalogueInstallState.IMPORTED, s.install)
        assertFalse(s.canImportFromCatalogue)
    }

    @Test fun higherListedVersionIsAnAvailableUpdateOnly() {
        assertTrue(CatalogueMatcher.classify(entry(versionCode = 13), facts(versionCode = 12), emptySet()).updateAvailable)
        assertFalse(CatalogueMatcher.classify(entry(versionCode = 12), facts(versionCode = 12), emptySet()).updateAvailable)
        assertFalse(CatalogueMatcher.classify(entry(versionCode = 11), facts(versionCode = 12), emptySet()).updateAvailable)
    }

    @Test fun signerMismatchBlocksImportFromTheCatalogueView() {
        val s = CatalogueMatcher.classify(entry(), facts(signers = listOf(signerB)), emptySet())
        assertTrue(s.signerDiffers)
        assertFalse(s.canImportFromCatalogue)
    }

    @Test fun signerComparisonIgnoresFormatting() {
        val colonForm = signerA.uppercase().chunked(2).joinToString(":")
        assertFalse(CatalogueMatcher.classify(entry(), facts(signers = listOf(colonForm)), emptySet()).signerDiffers)
    }

    @Test fun anyInstalledSignerOutsideTheListedSetCountsAsDiffering() {
        assertTrue(CatalogueMatcher.classify(entry(), facts(signers = listOf(signerA, signerB)), emptySet()).signerDiffers)
        assertFalse(CatalogueMatcher.classify(entry(signers = listOf(signerA, signerB)), facts(signers = listOf(signerB)), emptySet()).signerDiffers)
    }

    @Test fun installedPackageWithNoUsableSignerCountsAsDiffering() {
        assertTrue(CatalogueMatcher.classify(entry(), facts(signers = emptyList()), emptySet()).signerDiffers)
        assertTrue(CatalogueMatcher.classify(entry(), facts(signers = listOf("garbage")), emptySet()).signerDiffers)
    }

    @Test fun notInstalledEntryNeverReportsASignerMismatch() {
        assertFalse(CatalogueMatcher.classify(entry(signers = listOf(signerB)), null, emptySet()).signerDiffers)
    }

    @Test fun apiLevelAboveTheHostIsIncompatibleAndBlocksImport() {
        val s = CatalogueMatcher.classify(entry(minCoreApi = PluginApi.LEVEL + 1, targetCoreApi = PluginApi.LEVEL + 1), facts(), emptySet())
        assertTrue(s.compatibility is Compatibility.Incompatible)
        assertFalse(s.canImportFromCatalogue)
    }

    @Test fun compatibleEntryReportsCompatible() {
        assertEquals(Compatibility.Compatible, CatalogueMatcher.classify(entry(), null, emptySet()).compatibility)
    }

    @Test fun missingDependenciesAreReportedInListedOrderAndDoNotBlockImport() {
        val e = entry(dependencies = listOf("com.example.b", "com.example.a", "com.example.c"))
        val s = CatalogueMatcher.classify(e, facts(), setOf("com.example.a"))
        assertEquals(listOf("com.example.b", "com.example.c"), s.missingDependencies)
        assertTrue(s.canImportFromCatalogue)
    }

    @Test fun enabledPluginsDoNotMakeAnEntryInstalled() {
        // Only the device facts decide the install state; nothing a source says can make a plugin look installed.
        val s = CatalogueMatcher.classify(entry(), null, setOf("com.example.notes"))
        assertEquals(CatalogueInstallState.NOT_INSTALLED, s.install)
    }

    @Test fun compatibilityOverloadMatchesTheManifestRules() {
        val hostLevel = PluginApi.LEVEL
        assertEquals(Compatibility.Compatible, PluginCompatibility.check(1, hostLevel))
        assertTrue(PluginCompatibility.check(hostLevel + 1, hostLevel + 1) is Compatibility.Incompatible)
        assertTrue(PluginCompatibility.check(1, 0) is Compatibility.CompatibleWithWarning)
        assertEquals(listOf("x.y"), PluginCompatibility.missingDependencies(listOf("x.y", "a.b"), setOf("a.b")))
    }
}
