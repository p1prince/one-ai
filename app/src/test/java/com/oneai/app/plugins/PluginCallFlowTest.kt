package com.oneai.app.plugins

import com.oneai.app.bubble.BubbleOutcomes
import com.oneai.app.core.PluginActionOutput
import com.oneai.app.core.PluginInvocationResult
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Slice C1 (decision D12): the pure call-flow state machine for in-app plugin use. */
class PluginCallFlowTest {
    private val outcomes = BubbleOutcomes("Unavailable", "Blocked", "Failed")
    private fun ok(title: String = "Echo", content: String = "hi") =
        PluginInvocationResult.Completed(PluginActionOutput(title, content))

    @Test fun startsIdle() {
        assertEquals(PluginCallState.Idle, PluginCallFlow().state())
    }

    @Test fun aCallWithoutApprovalRunsAtOnceAndShowsItsResult() {
        val flow = PluginCallFlow()
        val id = flow.request("Echo", "echo", needsApproval = false)
        assertNotNull(id)
        assertEquals(PluginCallState.Running("Running Echo..."), flow.state())
        assertTrue(flow.finish(id!!, ok(), outcomes))
        assertEquals(PluginCallState.Done("Echo", "hi", PluginCallOutcome.SUCCESS), flow.state())
    }

    @Test fun aSensitiveCallWaitsForApprovalThenRuns() {
        val flow = PluginCallFlow()
        val id = flow.request("Reverse", "reverse", needsApproval = true, review = "abc")!!
        assertEquals(PluginCallState.AwaitingApproval("Running Reverse...", "abc"), flow.state())
        assertFalse("nothing finishes before approval", flow.finish(id, ok(), outcomes))
        assertTrue(flow.approve(id))
        assertEquals(PluginCallState.Running("Running Reverse..."), flow.state())
        assertTrue(flow.finish(id, ok(), outcomes))
        assertTrue(flow.state() is PluginCallState.Done)
    }

    @Test fun aRunningCallCanMoveToApprovalWhenTheExecutorAsksForIt() {
        val flow = PluginCallFlow()
        val id = flow.request("Reverse", "reverse", needsApproval = false)!!
        assertTrue(flow.needApproval(id, "text: abc"))
        assertEquals(PluginCallState.AwaitingApproval("Running Reverse...", "text: abc"), flow.state())
        assertTrue(flow.approve(id))
        assertTrue(flow.state() is PluginCallState.Running)
        assertFalse("only a running call can ask for approval", flow.needApproval(id))
    }

    @Test fun approvalRequestForACancelledOrOlderRunIsDropped() {
        val flow = PluginCallFlow()
        val old = flow.request("A", "a", needsApproval = false)!!
        flow.cancel()
        assertFalse(flow.needApproval(old))
        flow.request("B", "b", needsApproval = false)
        assertFalse(flow.needApproval(old))
        assertTrue(flow.state() is PluginCallState.Running)
    }

    @Test fun rejectingAtApprovalReturnsToIdle() {
        val flow = PluginCallFlow()
        val id = flow.request("Reverse", "reverse", needsApproval = true)!!
        assertTrue(flow.reject(id))
        assertEquals(PluginCallState.Idle, flow.state())
        assertFalse("an approval cannot be reused after rejection", flow.approve(id))
    }

    @Test fun cancelWhileAwaitingApprovalReturnsToIdle() {
        val flow = PluginCallFlow()
        flow.request("Reverse", "reverse", needsApproval = true)
        assertTrue(flow.cancel())
        assertEquals(PluginCallState.Idle, flow.state())
    }

    @Test fun cancelWhileRunningDropsALateResult() {
        val flow = PluginCallFlow()
        val id = flow.request("Wait", "wait", needsApproval = false)!!
        assertTrue(flow.cancel())
        assertEquals(PluginCallState.Cancelled, flow.state())
        assertFalse("a result after Cancel must not be shown", flow.finish(id, ok(), outcomes))
        assertEquals(PluginCallState.Cancelled, flow.state())
        assertFalse("cancel is idempotent", flow.cancel())
    }

    @Test fun onlyOneCallRunsAtATime() {
        val flow = PluginCallFlow()
        val first = flow.request("A", "a", needsApproval = false)
        assertNotNull(first)
        assertNull(flow.request("B", "b", needsApproval = false))
        assertEquals(PluginCallState.Running("Running A..."), flow.state())
        val waiting = PluginCallFlow()
        waiting.request("A", "a", needsApproval = true)
        assertNull(waiting.request("B", "b", needsApproval = false))
    }

    @Test fun aNewCallIsAllowedAfterDoneOrCancelledAndOldResultsStayDropped() {
        val flow = PluginCallFlow()
        val first = flow.request("A", "a", needsApproval = false)!!
        flow.cancel()
        val second = flow.request("B", "b", needsApproval = false)!!
        assertNotEquals(first, second)
        assertFalse("the cancelled run's late result cannot finish the new run", flow.finish(first, ok(), outcomes))
        assertEquals(PluginCallState.Running("Running B..."), flow.state())
        assertTrue(flow.finish(second, ok("B done"), outcomes))
        val third = flow.request("C", "c", needsApproval = false)
        assertNotNull(third)
    }

    @Test fun approvalForAnOlderRunIsRefused() {
        val flow = PluginCallFlow()
        val old = flow.request("A", "a", needsApproval = true)!!
        flow.cancel()
        flow.request("B", "b", needsApproval = true)
        assertFalse(flow.approve(old))
        assertFalse(flow.reject(old))
        assertTrue(flow.state() is PluginCallState.AwaitingApproval)
    }

    @Test fun resultKindsMapToOutcomes() {
        fun run(result: PluginInvocationResult): PluginCallState {
            val flow = PluginCallFlow()
            val id = flow.request("X", "x", needsApproval = false)!!
            flow.finish(id, result, outcomes)
            return flow.state()
        }
        assertEquals(PluginCallState.Done("Blocked", "no", PluginCallOutcome.BLOCKED), run(PluginInvocationResult.Rejected("no")))
        assertEquals(PluginCallState.Done("Failed", "boom", PluginCallOutcome.FAILED), run(PluginInvocationResult.Failed("boom")))
        val expired = run(PluginInvocationResult.AwaitingApproval("again")) as PluginCallState.Done
        assertEquals(PluginCallOutcome.BLOCKED, expired.outcome)
    }

    @Test fun aSuccessThatChoosesToShowNothingReturnsToIdle() {
        val quiet = BubbleOutcomes("U", "B", "F", successTitle = { null })
        val flow = PluginCallFlow()
        val id = flow.request("X", "x", needsApproval = false)!!
        assertTrue(flow.finish(id, ok(), quiet))
        assertEquals(PluginCallState.Idle, flow.state())
    }

    @Test fun failWithoutAResultShowsAFailureAndIsGuarded() {
        val flow = PluginCallFlow()
        val id = flow.request("X", "x", needsApproval = false)!!
        assertTrue(flow.fail(id, "Failed", "unexpected"))
        assertEquals(PluginCallState.Done("Failed", "unexpected", PluginCallOutcome.FAILED), flow.state())
        assertFalse(flow.fail(id, "Failed", "again"))
    }

    @Test fun dismissClearsAResultOrCancelledNoteButNeverARunningCall() {
        val flow = PluginCallFlow()
        val id = flow.request("X", "x", needsApproval = false)!!
        assertFalse(flow.dismiss())
        flow.finish(id, ok(), outcomes)
        assertTrue(flow.dismiss())
        assertEquals(PluginCallState.Idle, flow.state())
        flow.request("Y", "y", needsApproval = false)
        flow.cancel()
        assertTrue(flow.dismiss())
        assertEquals(PluginCallState.Idle, flow.state())
        assertFalse(flow.dismiss())
    }

    @Test fun pluginTextIsCleanedAndBounded() {
        val flow = PluginCallFlow()
        val id = flow.request("X", "x", needsApproval = false)!!
        val hidden = "Safe‮txt​"
        flow.finish(id, ok(hidden + "T".repeat(200), "line one‮\nline​ two\n" + "c".repeat(9000)), outcomes)
        val done = flow.state() as PluginCallState.Done
        assertFalse(done.title.contains('‮') || done.title.contains('​'))
        assertTrue(done.title.length <= PluginCallFlow.MAX_TITLE)
        assertFalse(done.content.contains('‮') || done.content.contains('​'))
        assertTrue("line breaks are kept", done.content.startsWith("line one\nline two\n"))
        assertTrue(done.content.length <= PluginCallFlow.MAX_CONTENT)
    }

    @Test fun theApprovalReviewTextIsCleanedAndBounded() {
        val flow = PluginCallFlow()
        flow.request("X", "x", needsApproval = true, review = "go‮" + "r".repeat(2000))
        val waiting = flow.state() as PluginCallState.AwaitingApproval
        assertFalse(waiting.review!!.contains('‮'))
        assertTrue(waiting.review!!.length <= PluginCallFlow.MAX_REVIEW)
    }

    @Test fun theHeadingUsesTheCommandTitleOnly() {
        val flow = PluginCallFlow()
        flow.request("  Add   note ", "add_note", needsApproval = false)
        assertEquals(PluginCallState.Running("Running Add note..."), flow.state())
        flow.cancel()
        flow.request(null, "add_note", needsApproval = false)
        assertEquals(PluginCallState.Running("Running add_note..."), flow.state())
    }

    @Test fun sourceGuardsTheFlowIsPureAndNeverReadsBubbleAccess() {
        val dir = listOf("src/main/java/com/oneai/app/plugins", "app/src/main/java/com/oneai/app/plugins").map(::File).first { it.isDirectory }
        val source = File(dir, "PluginCallFlow.kt").readText()
        assertFalse("no Android types", source.contains("import android."))
        listOf("hasBubbleAccess", "setBubbleAccess", "bubbleBindings", "PluginManager", "FloatingBubbleService").forEach {
            assertFalse("the flow must not touch $it", source.contains(it))
        }
    }
}
