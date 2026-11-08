package com.oneai.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Stage 10, slice 10.3: the Activity filter is display-only, never deletes, and never loses an older entry under All. */
class ActivityFilterTest {
    private val entries = listOf(
        ActivityEntry(8L, "ai", ActivityKind.AI),
        ActivityEntry(7L, "cmd", ActivityKind.COMMAND),
        ActivityEntry(6L, "plugin", ActivityKind.PLUGIN, "com.example.notes"),
        ActivityEntry(5L, "approval", ActivityKind.APPROVAL, "com.example.notes"),
        ActivityEntry(4L, "error", ActivityKind.ERROR),
        ActivityEntry(3L, "system", ActivityKind.SYSTEM),
        ActivityEntry(2L, "older entry without a kind")
    )

    private fun source(relativePath: String): String {
        val candidates = listOf(File("src/main/java/$relativePath"), File("app/src/main/java/$relativePath"))
        return candidates.firstOrNull { it.isFile }?.readText() ?: error("Could not locate production source: $relativePath")
    }

    @Test fun allShowsEveryEntryIncludingOlderOnesInTheSameOrder() {
        assertEquals(entries, ActivityFilter.apply(entries, ActivityFilter.ALL))
    }

    @Test fun eachKindFilterShowsOnlyItsOwnKind() {
        assertEquals(listOf("ai"), ActivityFilter.apply(entries, ActivityFilter.AI).map { it.message })
        assertEquals(listOf("cmd"), ActivityFilter.apply(entries, ActivityFilter.COMMANDS).map { it.message })
        assertEquals(listOf("plugin"), ActivityFilter.apply(entries, ActivityFilter.PLUGINS).map { it.message })
        assertEquals(listOf("approval"), ActivityFilter.apply(entries, ActivityFilter.APPROVALS).map { it.message })
        assertEquals(listOf("error"), ActivityFilter.apply(entries, ActivityFilter.ERRORS).map { it.message })
    }

    @Test fun anEntryWithNoKindAppearsUnderAllOnly() {
        val older = entries.last()
        assertTrue(ActivityFilter.ALL.accepts(older))
        ActivityFilter.values().filter { it != ActivityFilter.ALL }.forEach { assertFalse(it.label, it.accepts(older)) }
    }

    @Test fun systemEntriesAppearUnderAllOnly() {
        val system = entries.first { it.kind == ActivityKind.SYSTEM }
        assertTrue(ActivityFilter.ALL.accepts(system))
        ActivityFilter.values().filter { it != ActivityFilter.ALL }.forEach { assertFalse(it.label, it.accepts(system)) }
    }

    @Test fun filtersPreserveNewestFirstOrderAndDoNotChangeTheInput() {
        val before = entries.toList()
        val mixed = listOf(
            ActivityEntry(9L, "p2", ActivityKind.PLUGIN), ActivityEntry(8L, "a", ActivityKind.AI), ActivityEntry(7L, "p1", ActivityKind.PLUGIN)
        )
        assertEquals(listOf("p2", "p1"), ActivityFilter.apply(mixed, ActivityFilter.PLUGINS).map { it.message })
        ActivityFilter.values().forEach { ActivityFilter.apply(entries, it) }
        assertEquals(before, entries)
        assertNotSame(entries, ActivityFilter.apply(entries, ActivityFilter.ALL))
    }

    @Test fun anEmptyLogGivesAnEmptyResultForEveryFilter() {
        ActivityFilter.values().forEach { assertTrue(ActivityFilter.apply(emptyList(), it).isEmpty()) }
    }

    @Test fun everyKindExceptSystemIsReachableThroughExactlyOneFilter() {
        ActivityKind.values().filter { it != ActivityKind.SYSTEM }.forEach { kind ->
            val matching = ActivityFilter.values().filter { it != ActivityFilter.ALL && it.accepts(ActivityEntry(1L, "x", kind)) }
            assertEquals(kind.name, 1, matching.size)
        }
    }

    @Test fun filterLabelsAreDistinctAndTheOrderStartsWithAll() {
        assertEquals(ActivityFilter.ALL, ActivityFilter.values().first())
        assertEquals(ActivityFilter.values().size, ActivityFilter.values().map { it.label }.toSet().size)
    }

    @Test fun theActivityScreenFiltersForDisplayOnlyAndNeverClearsThroughTheFilter() {
        val main = source("com/oneai/app/MainActivity.kt")
        val page = main.substring(main.indexOf("private fun ActivityPage"), main.indexOf("private fun SettingsPage"))
        assertTrue(page.contains("ActivityFilter.apply(entries,filter)"))
        assertTrue("the list is drawn from the filtered entries", page.contains("items(shown,"))
        assertFalse("raw entries must not be drawn directly", page.contains("items(entries,"))
        // Clear history stays a separate, confirmed action over the whole log; selecting a chip only changes local state.
        assertEquals(1, Regex("""store\.clearActivity\(\)""").findAll(page).count())
        assertTrue(page.contains("filter=option"))
        assertFalse("the selection is not persisted", page.contains("store.set") || page.contains("putString"))
    }

    @Test fun theFilterSourceHasNoStorageOrClearCall() {
        val filter = source("com/oneai/app/core/ActivityFilter.kt")
        assertFalse(filter.contains("clear"))
        assertFalse(filter.contains("OneAiStore"))
        assertFalse(filter.contains("SharedPreferences"))
    }
}
