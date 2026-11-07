package com.oneai.app.plugins

import com.oneai.app.core.CatalogueSourceStorage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

/** Stage 9.7c: catalogue sources, persistence codec, refresh behaviour and redirect policy. Fake transport and storage only; no network. */
class CatalogueSourcesTest {
    private class FakeStorage(var json: String? = null) : CatalogueSourceStorage {
        var writes = 0
        override fun readCatalogueSources() = json
        override fun writeCatalogueSources(json: String) { this.json = json; writes++ }
    }

    private class FakeTransport(var respond: (String) -> Result<String>) : CatalogueTransport {
        val requested = ArrayList<String>()
        var lastMaxBytes = -1
        override fun fetch(url: String, maxBytes: Int): Result<String> {
            requested += url
            lastMaxBytes = maxBytes
            return respond(url)
        }
    }

    private val urlA = "https://a.example.com/catalogue.json"
    private val urlB = "https://b.example.com/list.json?token=secret"
    private val log = ArrayList<String>()
    private val storage = FakeStorage()
    private val transport = FakeTransport { Result.failure(IOException("offline")) }
    private var clock = 1_000L
    private fun sources() = CatalogueSources(storage, transport, { clock }, { log += it })

    private fun doc(name: String = "Source A", ids: List<String> = listOf("com.example.notes"), skipOne: Boolean = false): String {
        val entries = JSONArray()
        ids.forEach { id ->
            entries.put(JSONObject().put("id", id).put("name", "N").put("versionName", "1.0.0").put("versionCode", 1)
                .put("minCoreApi", 1).put("targetCoreApi", 3).put("signerSha256", JSONArray().put("ab".repeat(32)))
                .put("getUrl", "https://example.com/get"))
        }
        if (skipOne) entries.put(JSONObject().put("id", "broken"))
        return JSONObject().put("catalogueVersion", 1).put("name", name).put("entries", entries).toString()
    }

    // ---- Defaults and management ---------------------------------------------------------------------------

    @Test fun thereAreNoSourcesByDefaultAndNothingIsFetched() {
        val s = sources()
        assertTrue(s.sources().isEmpty())
        assertTrue(s.listings().isEmpty())
        assertTrue(transport.requested.isEmpty())
        assertEquals(0, storage.writes)
    }

    @Test fun addingASourceValidatesItAndNeverContactsIt() {
        val s = sources()
        assertTrue(s.add(urlA).isSuccess)
        assertEquals(listOf(urlA), s.sources().map { it.url })
        assertTrue(transport.requested.isEmpty())
    }

    @Test fun unsafeSourceUrlsAreRefusedAndNothingIsStored() {
        val s = sources()
        listOf("http://a.example.com/x", "https://u:p@a.example.com/x", "ftp://a.example.com", "", "not a url").forEach {
            assertTrue("should refuse: $it", s.add(it).isFailure)
        }
        assertTrue(s.sources().isEmpty())
        assertEquals(0, storage.writes)
    }

    @Test fun duplicateSourcesAreRefusedIgnoringCase() {
        val s = sources()
        assertTrue(s.add(urlA).isSuccess)
        assertTrue(s.add(urlA.uppercase().replace("HTTPS", "https")).isFailure)
        assertEquals(1, s.sources().size)
    }

    @Test fun atMostEightSourcesAreAllowed() {
        val s = sources()
        (1..CatalogueSources.MAX_SOURCES).forEach { assertTrue(s.add("https://h$it.example.com/c.json").isSuccess) }
        assertTrue(s.add("https://h9.example.com/c.json").isFailure)
        assertEquals(CatalogueSources.MAX_SOURCES, s.sources().size)
    }

    @Test fun removingASourceDeletesItsListing() {
        val s = sources()
        s.add(urlA)
        transport.respond = { Result.success(doc()) }
        s.refreshAll()
        assertEquals(1, s.listings().single().document?.entries?.size)
        assertTrue(s.remove(urlA))
        assertTrue(s.listings().isEmpty())
        assertFalse(s.remove(urlA))
    }

    // ---- Refresh -------------------------------------------------------------------------------------------

    @Test fun successfulRefreshStoresTheValidatedDocumentAndMetadata() {
        val s = sources()
        s.add(urlA)
        transport.respond = { Result.success(doc(ids = listOf("com.example.one", "com.example.two"), skipOne = true)) }
        val outcome = s.refreshAll().single()
        assertNull(outcome.error)
        assertEquals(2, outcome.entryCount)
        assertEquals(1, outcome.skippedEntries)
        val record = s.sources().single()
        assertEquals("Source A", record.name)
        assertNull(record.lastError)
        assertEquals(1_000L, record.lastRefreshedMillis)
        assertEquals(listOf("com.example.one", "com.example.two"), s.listings().single().document?.entries?.map { it.id })
        assertEquals(CatalogueValidator.MAX_DOCUMENT_BYTES, transport.lastMaxBytes)
    }

    @Test fun failedRefreshKeepsThePreviousListingAndRecordsASafeError() {
        val s = sources()
        s.add(urlA)
        transport.respond = { Result.success(doc()) }
        s.refreshAll()
        transport.respond = { Result.failure(IOException("secret internal detail")) }
        clock = 2_000L
        val outcome = s.refreshAll().single()
        assertEquals("Could not reach the source", outcome.error)
        val record = s.sources().single()
        assertEquals("Could not reach the source", record.lastError)
        assertEquals(1_000L, record.lastRefreshedMillis)
        assertEquals(1, s.listings().single().document?.entries?.size)
        assertFalse(log.any { it.contains("secret internal detail") })
    }

    @Test fun firstRefreshFailureLeavesAnEmptyListingNotAnError() {
        val s = sources()
        s.add(urlA)
        s.refreshAll()
        assertNull(s.listings().single().document)
        assertNotNull(s.sources().single().lastError)
    }

    @Test fun invalidDocumentIsRejectedAndThePreviousListingIsKept() {
        val s = sources()
        s.add(urlA)
        transport.respond = { Result.success(doc()) }
        s.refreshAll()
        transport.respond = { Result.success("{\"catalogueVersion\":2}") }
        val outcome = s.refreshAll().single()
        assertTrue(outcome.error!!.startsWith("Not a usable catalogue"))
        assertEquals("Source A", s.sources().single().name)
        assertEquals(1, s.listings().single().document?.entries?.size)
    }

    @Test fun duplicateIdDocumentIsRejectedWhole() {
        val s = sources()
        s.add(urlA)
        transport.respond = { Result.success(doc(ids = listOf("com.example.same", "com.example.same"))) }
        assertNotNull(s.refreshAll().single().error)
        assertNull(s.listings().single().document)
    }

    @Test fun refreshContactsEverySourceExactlyOnceAndIsolatesFailures() {
        val s = sources()
        s.add(urlA); s.add(urlB)
        transport.respond = { url -> if (url == urlA) Result.failure(IOException()) else Result.success(doc("Source B")) }
        val outcomes = s.refreshAll()
        assertEquals(listOf(urlA, urlB), transport.requested)
        assertNotNull(outcomes[0].error)
        assertNull(outcomes[1].error)
        assertEquals("Source B", s.sources()[1].name)
    }

    @Test fun aSourceRemovedDuringRefreshIsNotBroughtBack() {
        val s = sources()
        s.add(urlA)
        transport.respond = { s.remove(urlA); Result.success(doc()) }
        s.refreshAll()
        assertTrue(s.sources().isEmpty())
    }

    @Test fun refreshDoesNotChangeAnythingForAnEmptySourceList() {
        val s = sources()
        assertTrue(s.refreshAll().isEmpty())
        assertTrue(transport.requested.isEmpty())
    }

    @Test fun activityLogNamesTheHostOnlyNeverPathsQueriesOrContent() {
        val s = sources()
        s.add(urlB)
        transport.respond = { Result.success(doc("Hidden Name")) }
        s.refreshAll()
        transport.respond = { Result.failure(IOException()) }
        s.refreshAll()
        assertTrue(log.isNotEmpty())
        assertTrue(log.all { !it.contains("token=secret") && !it.contains("list.json") && !it.contains("Hidden Name") })
        assertTrue(log.any { it.contains("b.example.com") })
    }

    // ---- Persistence and damaged data ---------------------------------------------------------------------

    @Test fun sourcesSurviveANewInstanceOverTheSameStorage() {
        val first = sources()
        first.add(urlA)
        transport.respond = { Result.success(doc()) }
        first.refreshAll()
        val second = CatalogueSources(storage, transport, { clock }, {})
        assertEquals(urlA, second.sources().single().url)
        assertEquals(1, second.listings().single().document?.entries?.size)
    }

    @Test fun unreadableStoredDataIsDiscardedToNoSourcesAndReportedOnce() {
        storage.json = "not json"
        val s = sources()
        assertTrue(s.sources().isEmpty())
        assertTrue(s.listings().isEmpty())
        assertEquals(1, log.count { it.contains("unreadable") })
    }

    @Test fun invalidStoredRecordsAreDroppedAndTheRestKept() {
        storage.json = JSONArray()
            .put(JSONObject().put("url", "http://insecure.example.com/x"))
            .put(JSONObject().put("url", urlA))
            .put("junk").toString()
        val s = sources()
        assertEquals(listOf(urlA), s.sources().map { it.url })
    }

    @Test fun aStoredDocumentThatNoLongerValidatesYieldsNoListing() {
        storage.json = CatalogueSourceCodec.encode(listOf(CatalogueSourceRecord(urlA, "S", "{\"catalogueVersion\":9}", null, 5)))
        assertNull(sources().listings().single().document)
    }

    @Test fun codecRoundTripsAndEnforcesDuplicatesAndTheSourceCap() {
        val records = (1..CatalogueSources.MAX_SOURCES + 2).map { CatalogueSourceRecord("https://h$it.example.com/c.json") }
        val decoded = CatalogueSourceCodec.decode(CatalogueSourceCodec.encode(records + records.first()))!!
        assertEquals(CatalogueSources.MAX_SOURCES, decoded.records.size)
        assertTrue(decoded.dropped >= 3)
        val one = CatalogueSourceRecord(urlA, "Name", "{}", "err", 77L)
        assertEquals(one, CatalogueSourceCodec.decode(CatalogueSourceCodec.encode(listOf(one)))!!.records.single())
    }

    @Test fun codecRejectsWrongTypesAndOversizeFields() {
        val bad = JSONArray()
            .put(JSONObject().put("url", urlA).put("name", 5))
            .put(JSONObject().put("url", urlA).put("refreshed", "now"))
            .put(JSONObject().put("url", urlA).put("error", "e".repeat(CatalogueSourceCodec.MAX_ERROR + 1)))
            .put(JSONObject().put("url", urlA).put("name", "n".repeat(CatalogueValidator.MAX_DOCUMENT_NAME + 1)))
            .toString()
        val decoded = CatalogueSourceCodec.decode(bad)!!
        assertTrue(decoded.records.isEmpty())
        assertEquals(4, decoded.dropped)
        assertNull(CatalogueSourceCodec.decode("{}"))
    }

    // ---- Redirect policy and gate ---------------------------------------------------------------------------

    @Test fun redirectsMayOnlyLeadToHttps() {
        assertEquals("https://a.example.com/new.json", RedirectPolicy.resolve(urlA, "/new.json").getOrThrow())
        assertEquals("https://other.example.org/x", RedirectPolicy.resolve(urlA, "https://other.example.org/x").getOrThrow())
        assertEquals("https://evil.example.net/x", RedirectPolicy.resolve(urlA, "//evil.example.net/x").getOrThrow())
        listOf("http://a.example.com/x", "ftp://a.example.com/x", "javascript:alert(1)", "https://u:p@a.example.com/x", "", "  ", null)
            .forEach { assertTrue("should refuse: $it", RedirectPolicy.resolve(urlA, it).isFailure) }
        assertTrue(RedirectPolicy.MAX_REDIRECTS in 1..5)
    }

    @Test fun refreshGateIsOneShot() {
        val gate = CatalogueRefreshGate()
        assertFalse(gate.consume())
        gate.request()
        assertTrue(gate.consume())
        assertFalse(gate.consume())
    }

    // ---- Production composition ---------------------------------------------------------------------------

    private fun source(relativePath: String): String =
        listOf(File("src/main/java/$relativePath"), File("app/src/main/java/$relativePath")).first { it.isFile }.readText()

    @Test fun productionStartupNeverRefreshesACatalogue() {
        val appServices = source("com/oneai/app/AppServices.kt")
        assertTrue(appServices.contains("CatalogueSources("))
        assertFalse("Startup must not fetch any catalogue.", appServices.contains("refreshAll"))
        assertFalse(source("com/oneai/app/OneAiApplication.kt").contains("refreshAll"))
    }

    @Test fun coreStorageStaysFreeOfPluginTypes() {
        assertFalse(source("com/oneai/app/core/CatalogueSourceStorage.kt").contains("com.oneai.app.plugins"))
    }

    @Test fun transportOnlyEverUsesHttpsAndNeverFollowsRedirectsAutomatically() {
        val transport = source("com/oneai/app/plugins/HttpsCatalogueTransport.kt")
        assertTrue(transport.contains("instanceFollowRedirects = false"))
        assertTrue(transport.contains("HttpsURLConnection"))
        assertFalse(transport.contains("http://"))
    }
}
