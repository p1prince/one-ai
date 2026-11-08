package com.oneai.app.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Stage 9.10: the wording of the update-check report and the wiring that keeps it honest. */
class UpdateCheckReportTest {
    private fun report(
        checked: Int = 0, unchanged: Int = 0, updated: Int = 0,
        needsApproval: List<String> = emptyList(), rejected: List<String> = emptyList(),
        unreadable: List<String> = emptyList(), notFound: List<String> = emptyList()
    ) = UpdateCheckReport(1L, checked, unchanged, updated, needsApproval, rejected, unreadable, notFound)

    private fun source(relativePath: String): String {
        val candidates = listOf(File("src/main/java/$relativePath"), File("app/src/main/java/$relativePath"))
        return candidates.firstOrNull { it.isFile }?.readText() ?: error("Could not locate production source: $relativePath")
    }

    @Test fun singularAndPluralAreWordedCorrectly() {
        assertEquals("Checked 2 plugins: 2 unchanged.", report(checked = 2, unchanged = 2).summary())
        assertEquals("Checked 1 plugin: 1 needs your approval.", report(checked = 1, needsApproval = listOf("A")).summary())
        assertEquals("Checked 2 plugins: 2 need your approval.", report(checked = 2, needsApproval = listOf("A", "B")).summary())
    }

    @Test fun everyOutcomeAppearsInAFixedOrder() {
        val all = report(
            checked = 5, unchanged = 2, updated = 1, needsApproval = listOf("A"), rejected = listOf("B"),
            unreadable = listOf("C"), notFound = listOf("D")
        )
        assertEquals(
            "Checked 6 plugins: 1 updated, 1 needs your approval, 1 refused, 1 could not be read, 1 no longer installed, 2 unchanged.",
            all.summary()
        )
        assertEquals(
            listOf(all.summary(), "Needs your approval: A", "Refused and turned off: B", "Could not be read: C", "No longer installed: D"),
            all.lines()
        )
    }

    @Test fun anAllClearReportIsOneLine() {
        assertEquals(1, report(checked = 3, unchanged = 2, updated = 1).lines().size)
    }

    @Test fun longNameListsAreShortened() {
        val names = (1..7).map { "n$it" }
        val line = report(checked = 7, needsApproval = names).lines()[1]
        assertEquals("Needs your approval: n1, n2, n3, n4, n5 and 2 more", line)
    }

    @Test fun pluginControlledNamesAreCleanedAndCut() {
        val hostile = "Evil\u202Eapp\nName"
        val long = "x".repeat(200)
        val lines = report(checked = 2, needsApproval = listOf(hostile, long)).lines()
        val group = lines[1]
        assertFalse(group.contains('\u202E'))
        assertFalse(group.contains('\n'))
        assertTrue(group.contains("Evilapp Name"))
        assertTrue(group.length <= "Needs your approval: ".length + UpdateCheckReport.MAX_NAME_LENGTH * 2 + 2)
    }

    @Test fun theUpdateButtonNoLongerReplacesTheScreenWithAnEmptyOneWhenACheckFails() {
        val main = source("com/oneai/app/MainActivity.kt")
        val start = main.indexOf("updateCheckGate.request()")
        val end = main.indexOf("Text(\"Check updates\"")
        assertTrue(start in 0 until end)
        val handler = main.substring(start, end)
        assertFalse(handler.contains("PluginCenterSnapshot.EMPTY"))
        assertTrue(handler.contains("updateCheckError"))
        assertTrue(handler.contains("lastUpdateReport"))
    }

    @Test fun productionRecordsEachCheckInActivityAsAPluginEntry() {
        val appServices = source("com/oneai/app/AppServices.kt")
        assertTrue(appServices.contains("onUpdateCheck"))
        assertTrue(appServices.contains("ActivityKind.PLUGIN"))
    }
}
