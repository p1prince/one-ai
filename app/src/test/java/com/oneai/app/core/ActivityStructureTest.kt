package com.oneai.app.core

import com.oneai.app.plugins.PluginActivityView
import com.oneai.app.security.ActionAuditLogger
import com.oneai.app.security.ActionRequest
import com.oneai.app.security.PluginActionGateway
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Stage 10, slice 10.2: structured Activity entries (optional kind and plugin id), additive and backward compatible. */
class ActivityStructureTest {
    private class MemoryStore {
        @Volatile var data: List<ActivityEntry> = emptyList()
        val load: () -> List<ActivityEntry> = { data }
        val save: (List<ActivityEntry>) -> Unit = { next -> Thread.sleep(0, 200_000); data = next }
    }

    private fun source(relativePath: String): String {
        val candidates = listOf(File("src/main/java/$relativePath"), File("app/src/main/java/$relativePath"))
        return candidates.firstOrNull { it.isFile }?.readText() ?: error("Could not locate production source: $relativePath")
    }

    // Codec: old data loads unchanged, new data round-trips.

    @Test fun anEntryStoredBeforeThisSliceLoadsWithNoKindAndNoPlugin() {
        val entry = ActivityCodec.decode(JSONObject("""{"time":42,"message":"Enabled plugin: Notes"}"""))
        assertEquals(42L, entry.timestamp)
        assertEquals("Enabled plugin: Notes", entry.message)
        assertNull(entry.kind)
        assertNull(entry.plugin)
    }

    @Test fun anEntryWithKindAndPluginRoundTrips() {
        val original = ActivityEntry(7L, "Sensitive action approved: com.example.notes/save", ActivityKind.APPROVAL, "com.example.notes")
        assertEquals(original, ActivityCodec.decode(ActivityCodec.encode(original)))
    }

    @Test fun everyKindRoundTripsThroughItsStoredValue() {
        ActivityKind.values().forEach { kind ->
            assertEquals(kind, ActivityKind.parse(kind.stored))
            assertEquals(kind, ActivityCodec.decode(ActivityCodec.encode(ActivityEntry(1L, "m", kind))).kind)
        }
        assertEquals(ActivityKind.values().size, ActivityKind.values().map { it.stored }.toSet().size)
    }

    @Test fun anEntryWithoutKindOrPluginIsWrittenWithoutThoseKeys() {
        val json = ActivityCodec.encode(ActivityEntry(5L, "old style"))
        assertFalse(json.has("kind"))
        assertFalse(json.has("plugin"))
        assertEquals(5L, json.getLong("time"))
        assertEquals("old style", json.getString("message"))
    }

    @Test fun anUnknownKindOrEmptyPluginReadsAsAbsent() {
        val entry = ActivityCodec.decode(JSONObject("""{"time":1,"message":"x","kind":"surprise","plugin":""}"""))
        assertNull(entry.kind)
        assertNull(entry.plugin)
        assertNull(ActivityKind.parse(null))
        assertNull(ActivityKind.parse(""))
        assertNull(ActivityKind.parse("AI"))
    }

    // The locked log: plain appends are system entries, typed appends keep their fields, order and cap are unchanged.

    @Test fun aPlainAppendIsASystemEntryWithNoPlugin() {
        val store = MemoryStore()
        val log = ActivityLog(store.load, store.save)
        log.append("plain")
        assertEquals(ActivityKind.SYSTEM, log.entries().single().kind)
        assertNull(log.entries().single().plugin)
    }

    @Test fun aTypedAppendKeepsItsKindAndPluginAndAnEmptyPluginIsDropped() {
        val store = MemoryStore()
        val log = ActivityLog(store.load, store.save)
        log.append("typed", ActivityKind.PLUGIN, "com.example.notes")
        log.append("blank", ActivityKind.ERROR, "")
        val entries = log.entries()
        assertEquals("blank", entries[0].message)
        assertEquals(ActivityKind.ERROR, entries[0].kind)
        assertNull(entries[0].plugin)
        assertEquals(ActivityKind.PLUGIN, entries[1].kind)
        assertEquals("com.example.notes", entries[1].plugin)
    }

    @Test fun orderAndCapAreUnchangedForMixedOldAndNewEntries() {
        val store = MemoryStore()
        store.data = listOf(ActivityEntry(1L, "earlier"))
        var clock = 1L
        val log = ActivityLog(store.load, store.save, { ++clock })
        repeat(ActivityLog.MAX_ENTRIES + 10) { log.append("e$it", ActivityKind.AI) }
        val entries = log.entries()
        assertEquals(ActivityLog.MAX_ENTRIES, entries.size)
        assertEquals("e${ActivityLog.MAX_ENTRIES + 9}", entries.first().message)
        assertEquals(entries.sortedByDescending { it.timestamp }, entries)
        assertTrue(entries.none { it.message == "earlier" })
    }

    @Test fun concurrentTypedWritersStillLoseNothing() {
        val store = MemoryStore()
        val threads = 8
        val each = 15
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        repeat(threads) { t ->
            val log = ActivityLog(store.load, store.save)
            pool.execute {
                start.await()
                repeat(each) { n -> log.append("t$t-$n", ActivityKind.PLUGIN, "com.example.p$t") }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue("writers did not finish", done.await(30, TimeUnit.SECONDS))
        pool.shutdown()
        assertEquals(threads * each, store.data.map { it.message }.toSet().size)
        assertTrue(store.data.all { it.kind == ActivityKind.PLUGIN && it.plugin != null })
    }

    // Plugin matching: the field wins, text matching is only the fallback.

    @Test fun thePluginFieldIsPreferredOverTheText() {
        val about = ActivityEntry(2L, "Something happened", ActivityKind.PLUGIN, "com.example.notes")
        assertTrue(PluginActivityView.isAbout(about, "com.example.notes"))
        // The text names another plugin, but the field says this entry is about the first one.
        val misleading = ActivityEntry(3L, "[com.example.other] hello", ActivityKind.PLUGIN, "com.example.notes")
        assertTrue(PluginActivityView.isAbout(misleading, "com.example.notes"))
        assertFalse(PluginActivityView.isAbout(misleading, "com.example.other"))
    }

    @Test fun anIdThatIsOnlyAPrefixOfAnotherNeverMatchesTheField() {
        val entry = ActivityEntry(4L, "x", ActivityKind.PLUGIN, "com.example.notes.extras")
        assertFalse(PluginActivityView.isAbout(entry, "com.example.notes"))
        assertTrue(PluginActivityView.isAbout(entry, "com.example.notes.extras"))
    }

    @Test fun anEntryWithNoPluginFieldFallsBackToWholeIdentifierTextMatching() {
        val old = ActivityEntry(1L, "[com.example.notes] Echo failed")
        assertTrue(PluginActivityView.isAbout(old, "com.example.notes"))
        val longer = ActivityEntry(1L, "[com.example.notes.extras] Echo failed")
        assertFalse(PluginActivityView.isAbout(longer, "com.example.notes"))
    }

    @Test fun forPluginMixesTypedAndOldEntriesNewestFirstAndBounded() {
        val entries = listOf(
            ActivityEntry(5L, "typed", ActivityKind.APPROVAL, "com.example.notes"),
            ActivityEntry(4L, "[com.example.notes] old"),
            ActivityEntry(3L, "unrelated", ActivityKind.SYSTEM),
            ActivityEntry(2L, "typed other", ActivityKind.PLUGIN, "com.example.other")
        )
        assertEquals(listOf("typed", "[com.example.notes] old"), PluginActivityView.forPlugin(entries, "com.example.notes").map { it.message })
        assertEquals(listOf("typed"), PluginActivityView.forPlugin(entries, "com.example.notes", limit = 1).map { it.message })
    }

    // Typed call sites: gateway, registrar and AI orchestrator. A plugin cannot choose its own entry's kind.

    @Test fun theGatewayRecordsApprovalEntriesTypedWithThePluginId() {
        val recorded = mutableListOf<Triple<String, ActivityKind, String?>>()
        val logger = object : ActionAuditLogger {
            override fun record(event: String) { error("untyped record must not be used by the gateway") }
            override fun record(event: String, kind: ActivityKind, plugin: String?) { recorded += Triple(event, kind, plugin) }
        }
        val gateway = PluginActionGateway({ null }, logger)
        gateway.check(ActionRequest("com.example.missing", "act", "commands", "d"))
        val entry = recorded.single()
        assertEquals(ActivityKind.APPROVAL, entry.second)
        assertEquals("com.example.missing", entry.third)
    }

    @Test fun anUntypedLoggerStillReceivesTheMessageText() {
        val events = mutableListOf<String>()
        val gateway = PluginActionGateway({ null }, ActionAuditLogger { events += it })
        gateway.check(ActionRequest("com.example.missing", "act", "commands", "d"))
        assertEquals(1, events.size)
        assertTrue(events.single().contains("com.example.missing/act"))
    }

    @Test fun everyGatewayAuditCallIsTyped() {
        val gateway = source("com/oneai/app/security/PluginActionGateway.kt")
        val calls = Regex("""audit\.record\(""").findAll(gateway).count()
        val typed = Regex("""audit\.record\(.*ActivityKind\.""").findAll(gateway).count()
        assertTrue(calls > 0)
        assertEquals(calls, typed)
    }

    @Test fun registrarAndAiOrchestratorLogPluginAndAiEntriesTyped() {
        val registrar = source("com/oneai/app/plugins/ExternalPluginRegistrar.kt")
        assertTrue(registrar.contains("""store.log("[${'$'}pluginId] ${'$'}message", ActivityKind.PLUGIN, pluginId)"""))
        assertFalse(registrar.contains("""store.log("[${'$'}pluginId] ${'$'}message")"""))
        val orchestrator = source("com/oneai/app/ai/AiOrchestrator.kt")
        assertEquals(3, Regex("""activity\.log\(.*ActivityKind\.""").findAll(orchestrator).count())
        assertTrue(orchestrator.contains("ActivityKind.ERROR"))
    }

    @Test fun theKindIsNeverTakenFromPluginText() {
        val registrar = source("com/oneai/app/plugins/ExternalPluginRegistrar.kt")
        assertFalse("a plugin message must not decide the kind", registrar.contains("ActivityKind.parse"))
        val adapter = source("com/oneai/app/plugins/ExternalPluginAdapter.kt")
        assertFalse(adapter.contains("ActivityKind"))
    }

    @Test fun storeWritesAndReadsThroughTheCodecOnly() {
        val store = source("com/oneai/app/core/OneAiStore.kt")
        assertTrue(store.contains("ActivityCodec.encode"))
        assertTrue(store.contains("ActivityCodec.decode"))
        assertFalse(store.contains("put(\"kind\""))
    }
}
