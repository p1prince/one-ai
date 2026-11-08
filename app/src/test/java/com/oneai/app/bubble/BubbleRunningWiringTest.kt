package com.oneai.app.bubble

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Slice B2 (decision D11 / D-T): source guards that every Bubble execution path that used to hide the panel
 * now shows the host-drawn "Running" panel with Cancel. These read source; they do not render the overlay.
 */
class BubbleRunningWiringTest {
    private val dir = listOf("src/main/java/com/oneai/app/bubble", "app/src/main/java/com/oneai/app/bubble").map(::File).first { it.isDirectory }
    private val service = File(dir, "FloatingBubbleService.kt").readText()

    private fun body(source: String, start: String, end: String): String {
        val from = source.indexOf(start)
        assertTrue("missing $start", from >= 0)
        val to = source.indexOf(end, from + start.length)
        assertTrue("missing $end", to > from)
        return source.substring(from, to)
    }

    @Test fun allThreeExecutionPathsRunBehindTheRunningPanel() {
        assertTrue(body(service, "private fun runPluginCall(", "private fun showApproval(").contains("launchRun("))
        assertTrue(body(service, "private fun showApproval(", "private fun runGatedHostAction(").contains("launchRun("))
        assertTrue(body(service, "private fun showCommandApproval(", "private fun <T> launchRun(").contains("launchRun("))
    }

    @Test fun noApprovedPathHidesThePanelAndThenWaitsInSilence() {
        assertFalse(body(service, "private fun showApproval(", "private fun runGatedHostAction(").contains("hidePanel()"))
        assertFalse(body(service, "private fun showCommandApproval(", "private fun <T> launchRun(").contains("hidePanel()"))
    }

    @Test fun theRunningPanelShowsTheCommandTitleOnlyAndHasCancel() {
        val panel = body(service, "private fun showRunning(", "/** Generic rendering only;")
        assertTrue(panel.contains("BubbleRunning.heading(title, fallback)"))
        assertTrue(panel.contains("addButton(box, \"Cancel\")"))
        assertTrue(panel.contains("runs.cancel(id)"))
        assertTrue(panel.contains("BubbleRunning.CANCELLED_NOTE"))
        listOf("outcomes", "description", "review", "output", "content").forEach {
            assertFalse("the Running panel must not show plugin text ($it)", panel.contains(it))
        }
    }

    @Test fun aLateResultIsDroppedAndAFailureDoesNotCrashTheService() {
        val run = body(service, "private fun <T> launchRun(", "private fun showRunning(")
        assertTrue(run.contains("runs.complete(id)"))
        assertTrue(run.contains("CancellationException"))
        assertTrue(run.contains("runs.attach(id, job)"))
        assertTrue(run.contains("Result.failure<T>(error)"))
    }

    @Test fun theServiceHoldsOneRunStateAndHidingThePanelDoesNotCancel() {
        assertEquals(1, Regex("BubbleRunState\\(\\)").findAll(service).count())
        assertFalse(body(service, "private fun hidePanel()", "private fun overlayType()").contains("runs."))
    }

    @Test fun externalPluginButtonsCarryTheCommandTitleForThePanel() {
        val provider = File(dir, "ExternalBubbleProvider.kt").readText()
        assertTrue(provider.contains("runTitle = command.title"))
    }

    private fun repoFile(relative: String): File =
        listOf(File("../$relative"), File(relative), File("../../$relative")).firstOrNull { it.isFile }
            ?: error("Could not locate $relative")

    @Test fun theDocumentsDescribeTheRunningPanelAndWhereArgumentsComeFrom() {
        val sdk = repoFile("PLUGIN_SDK.md").readText()
        val guide = repoFile("docs/PLUGIN_DEVELOPER_GUIDE.md").readText()
        listOf(sdk, guide).forEach {
            assertTrue(it.contains("**Running** panel"))
            assertTrue(it.contains("**Cancel**"))
            assertTrue(it.contains("35"))
            assertTrue(it.contains("declares no parameters"))
        }
        val plan = repoFile("docs/EMULATOR_TEST_PLAN.md").readText()
        listOf("M36", "M37", "M38", "M39", "M40").forEach { assertTrue("$it missing", plan.contains("| $it |")) }
    }

    @Test fun runTitleIsOptionalSoOtherContributorsKeepWorking() {
        val call = BubbleAction.PluginCall(
            pluginId = "dev.example.notes",
            input = com.oneai.app.core.PluginActionInput("echo", "commands", "Run command: Echo"),
            approval = BubbleApproval("ALLOW ECHO?", "message", null, "Allow once"),
            outcomes = BubbleOutcomes("unavailable", "blocked", "failed")
        )
        assertNull(call.runTitle)
        assertEquals("Running echo...", BubbleRunning.heading(call.runTitle, call.input.actionId))
    }
}
