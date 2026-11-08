package com.oneai.app.core

import com.oneai.app.plugins.CatalogueView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Stage 10, A1 and A2: the Activity log keeps every concurrent entry, and plugin-supplied text is cleaned for display. */
class ActivityLogTest {
    /** A store that behaves like the real one: every append is a full read followed by a full write, with a pause between. */
    private class SlowStore {
        @Volatile var data: List<ActivityEntry> = emptyList()
        val load: () -> List<ActivityEntry> = { data }
        val save: (List<ActivityEntry>) -> Unit = { next -> Thread.sleep(0, 200_000); data = next }
    }

    private fun source(relativePath: String): String {
        val candidates = listOf(File("src/main/java/$relativePath"), File("app/src/main/java/$relativePath"))
        return candidates.firstOrNull { it.isFile }?.readText() ?: error("Could not locate production source: $relativePath")
    }

    @Test fun newestEntryComesFirst() {
        val store = SlowStore()
        var clock = 0L
        val log = ActivityLog(store.load, store.save, { ++clock })
        log.append("one"); log.append("two")
        assertEquals(listOf("two", "one"), log.entries().map { it.message })
        assertEquals(listOf(2L, 1L), log.entries().map { it.timestamp })
    }

    @Test fun listIsCutToTheMaximumAndKeepsTheNewest() {
        val store = SlowStore()
        val log = ActivityLog(store.load, store.save)
        repeat(ActivityLog.MAX_ENTRIES + 20) { log.append("e$it") }
        val entries = log.entries()
        assertEquals(ActivityLog.MAX_ENTRIES, entries.size)
        assertEquals("e${ActivityLog.MAX_ENTRIES + 19}", entries.first().message)
        assertEquals("e20", entries.last().message)
    }

    @Test fun clearEmptiesTheLog() {
        val store = SlowStore()
        val log = ActivityLog(store.load, store.save)
        log.append("x"); log.clear()
        assertTrue(log.entries().isEmpty())
    }

    @Test fun concurrentAppendsFromSeveralInstancesAreAllKept() {
        val store = SlowStore()
        val threads = 8
        val each = 15
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        repeat(threads) { t ->
            // One ActivityLog per thread, as with several OneAiStore instances over the same preferences.
            val log = ActivityLog(store.load, store.save)
            pool.execute {
                start.await()
                repeat(each) { n -> log.append("t$t-$n") }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue("writers did not finish", done.await(30, TimeUnit.SECONDS))
        pool.shutdown()
        val messages = store.data.map { it.message }
        assertEquals(threads * each, messages.size)
        assertEquals(threads * each, messages.toSet().size)
    }

    @Test fun displayCleaningStripsHiddenAndReorderingCharacters() {
        val raw = "Registered enabled external plugin: Safe‮evil​\u0007 name next"
        val shown = CatalogueView.clean(raw, ActivityLog.DISPLAY_MAX)
        assertFalse(shown.any { it == '‮' || it == '​' || it == '\u0007' || it == ' ' })
        assertEquals("Registered enabled external plugin: Safeevil name next", shown)
    }

    @Test fun displayCleaningCutsAtTheDisplayMaximum() {
        assertEquals(ActivityLog.DISPLAY_MAX, CatalogueView.clean("a".repeat(ActivityLog.DISPLAY_MAX + 50), ActivityLog.DISPLAY_MAX).length)
    }

    @Test fun activityScreenCleansEveryLogLineItShows() {
        val main = source("com/oneai/app/MainActivity.kt")
        val page = main.substring(main.indexOf("private fun ActivityPage"), main.indexOf("private fun SettingsPage"))
        assertTrue(page.contains("CatalogueView.clean(it.message"))
        assertFalse("raw message must not be shown", page.contains("Text(it.message"))
    }

    @Test fun storeAppendsOnlyThroughTheLockedLog() {
        val store = source("com/oneai/app/core/OneAiStore.kt")
        assertTrue(store.contains("activityLog.append(message)"))
        assertFalse("no direct write of the activity key outside the locked log", Regex("""writeArray\("activity", (?!JSONArray\(\)\.apply)""").containsMatchIn(store))
    }
}
