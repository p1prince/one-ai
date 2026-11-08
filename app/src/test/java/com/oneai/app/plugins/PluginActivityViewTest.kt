package com.oneai.app.plugins

import com.oneai.app.core.ActivityEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stage 9.9: which Activity entries count as being about one plugin. Pure JVM. */
class PluginActivityViewTest {
    private val id = "com.example.notes"

    @Test fun matchesEveryMessageShapeThatNamesThePlugin() {
        listOf(
            "[com.example.notes] Failed (E_TIMEOUT): add_note",
            "[com.example.notes] Timed out after 10s: add_note",
            "Low-risk action permitted: com.example.notes/add_note",
            "Sensitive action awaiting approval: com.example.notes/add_note",
            "Action rejected: not enabled (com.example.notes/add_note)",
            "Plugin action succeeded: com.example.notes/add_note",
            "Trusted com.example.notes.",
            "com.example.notes"
        ).forEach { assertTrue(it, PluginActivityView.mentions(it, id)) }
    }

    @Test fun aLongerOrDifferentIdNeverMatches() {
        listOf(
            "[com.example.notes.extras] Failed (E_TIMEOUT): add_note",
            "Low-risk action permitted: com.example.notes.extras/add_note",
            "[org.com.example.notes] Failed",
            "[com.example.notes2] Failed",
            "[com.example.notes-pro] Failed",
            "[com.example.note] Failed",
            "Nothing about plugins at all"
        ).forEach { assertFalse(it, PluginActivityView.mentions(it, id)) }
    }

    @Test fun aLaterWholeMatchIsFoundAfterAnEarlierPartialOne() {
        assertTrue(PluginActivityView.mentions("com.example.notes.extras blocked com.example.notes", id))
    }

    @Test fun emptyIdMatchesNothing() {
        assertFalse(PluginActivityView.mentions("anything", ""))
    }

    @Test fun forPluginKeepsOrderAndLimitsToTheNewest() {
        val log = (1..30).map { ActivityEntry(1000L - it, "[$id] event $it") } +
            ActivityEntry(1, "[com.example.other] event x")
        val picked = PluginActivityView.forPlugin(log, id)
        assertEquals(PluginActivityView.DEFAULT_LIMIT, picked.size)
        assertEquals("[$id] event 1", picked.first().message)
        assertEquals("[$id] event 10", picked.last().message)
        assertTrue(PluginActivityView.forPlugin(log, id, limit = 0).isEmpty())
        assertTrue(PluginActivityView.forPlugin(log, id, limit = -3).isEmpty())
        assertTrue(PluginActivityView.forPlugin(emptyList(), id).isEmpty())
    }

    @Test fun otherPluginsEntriesAreNotIncluded() {
        val log = listOf(ActivityEntry(2, "[com.example.other] Failed"), ActivityEntry(1, "[$id] Failed"))
        assertEquals(listOf("[$id] Failed"), PluginActivityView.forPlugin(log, id).map { it.message })
    }
}
