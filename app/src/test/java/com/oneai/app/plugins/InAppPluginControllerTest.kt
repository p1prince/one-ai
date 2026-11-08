package com.oneai.app.plugins

import com.oneai.app.bubble.BubbleAction
import com.oneai.app.bubble.BubbleApproval
import com.oneai.app.bubble.BubbleElement
import com.oneai.app.bubble.BubbleHostActionRequest
import com.oneai.app.bubble.BubbleOutcomes
import com.oneai.app.bubble.BubbleView
import com.oneai.app.core.PluginActionExecutor
import com.oneai.app.core.PluginActionInput
import com.oneai.app.core.PluginActionOutput
import com.oneai.app.core.PluginInvocationResult
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Slice C2 (decision D12): the controller behind the in-app plugin screen, driven with fake executors. */
class InAppPluginControllerTest {
    private val outcomes = BubbleOutcomes("Echo unavailable", "Echo blocked", "Echo failed")
    private val input = PluginActionInput("echo", "commands", "Run command: Echo", mapOf("text" to "hi"))
    private fun call(pluginId: String = "echo.plugin", review: String? = "text: hi") = BubbleAction.PluginCall(
        pluginId = pluginId,
        input = input,
        approval = BubbleApproval("ALLOW ECHO?", "Echo wants to run it.", review, "Allow once"),
        outcomes = outcomes,
        runTitle = "Echo"
    )

    private class FakeExecutor : PluginActionExecutor {
        val invokes = mutableListOf<String?>()
        var approveCalls = 0
        var approveResult: Result<String> = Result.success("ticket-1")
        var firstAnswer: suspend () -> PluginInvocationResult = { PluginInvocationResult.Completed(PluginActionOutput("Echo", "hi")) }
        var ticketAnswer: suspend () -> PluginInvocationResult = { PluginInvocationResult.Completed(PluginActionOutput("Echo", "approved")) }
        override suspend fun invoke(pluginId: String, input: PluginActionInput, approvalTicket: String?): PluginInvocationResult {
            invokes += approvalTicket
            return if (approvalTicket == null) firstAnswer() else ticketAnswer()
        }
        override fun approveSensitiveAction(pluginId: String, input: PluginActionInput): Result<String> {
            approveCalls += 1
            return approveResult
        }
    }

    private val jobs = mutableListOf<Job>()
    private fun controller(
        executor: PluginActionExecutor,
        loadView: suspend () -> BubbleView = { BubbleView(listOf(BubbleElement.Label("Echo"))) },
        prepare: suspend () -> Result<Unit> = { Result.success(Unit) }
    ): InAppPluginController {
        val job = Job().also { jobs += it }
        return InAppPluginController("echo.plugin", CoroutineScope(Dispatchers.Unconfined + job), executor, loadView, prepare)
    }

    @Test fun loadsThePluginScreen() {
        val c = controller(FakeExecutor())
        assertEquals(PluginViewState.Loading, c.ui.value.view)
        c.load()
        assertTrue(c.ui.value.view is PluginViewState.Loaded)
    }

    @Test fun aLoadFailureShowsAFixedMessageNotTheExceptionText() {
        val c = controller(FakeExecutor(), loadView = { throw IllegalStateException("secret plugin detail") })
        c.load()
        assertEquals(PluginViewState.Failed(InAppPluginController.LOAD_FAILED), c.ui.value.view)
    }

    @Test fun aPlainCallRunsAndShowsItsResult() {
        val executor = FakeExecutor()
        val c = controller(executor)
        c.press(call())
        assertEquals(PluginCallState.Done("Echo", "hi", PluginCallOutcome.SUCCESS), c.ui.value.call)
        assertEquals(listOf<String?>(null), executor.invokes)
    }

    @Test fun aSensitiveCallAsksThenRunsWithTheTicket() {
        val executor = FakeExecutor()
        executor.firstAnswer = { PluginInvocationResult.AwaitingApproval("needs approval") }
        val c = controller(executor)
        c.press(call())
        assertTrue(c.ui.value.call is PluginCallState.AwaitingApproval)
        assertEquals("ALLOW ECHO?", c.ui.value.approval?.title)
        assertEquals(0, executor.approveCalls)
        c.approve()
        assertEquals(1, executor.approveCalls)
        assertEquals(listOf<String?>(null, "ticket-1"), executor.invokes)
        assertEquals(PluginCallState.Done("Echo", "approved", PluginCallOutcome.SUCCESS), c.ui.value.call)
        assertNull(c.ui.value.approval)
    }

    @Test fun anExecutorThatStillAsksAfterTheTicketEndsInAResultNotAnApprovalLoop() {
        val executor = FakeExecutor()
        executor.firstAnswer = { PluginInvocationResult.AwaitingApproval("needs approval") }
        executor.ticketAnswer = { PluginInvocationResult.AwaitingApproval("still needs approval") }
        val c = controller(executor)
        c.press(call())
        c.approve()
        val done = c.ui.value.call as PluginCallState.Done
        assertEquals(PluginCallOutcome.BLOCKED, done.outcome)
        assertNull(c.ui.value.approval)
        assertEquals("one ticket, one run", 1, executor.approveCalls)
    }

    @Test fun rejectingNeverIssuesATicketOrRunsTheCommand() {
        val executor = FakeExecutor()
        executor.firstAnswer = { PluginInvocationResult.AwaitingApproval("needs approval") }
        val c = controller(executor)
        c.press(call())
        c.reject()
        assertEquals(PluginCallState.Idle, c.ui.value.call)
        assertEquals(0, executor.approveCalls)
        assertEquals(listOf<String?>(null), executor.invokes)
        c.approve()
        assertEquals("a rejected approval cannot be approved afterwards", 0, executor.approveCalls)
    }

    @Test fun anApprovalThatCannotBeVerifiedShowsAFailureAndDoesNotRun() {
        val executor = FakeExecutor()
        executor.firstAnswer = { PluginInvocationResult.AwaitingApproval("needs approval") }
        executor.approveResult = Result.failure(IllegalStateException("no"))
        val c = controller(executor)
        c.press(call())
        c.approve()
        assertEquals(PluginCallState.Done("Echo blocked", InAppPluginController.UNVERIFIED_NOTE, PluginCallOutcome.FAILED), c.ui.value.call)
        assertEquals(listOf<String?>(null), executor.invokes)
    }

    @Test fun cancelWhileRunningShowsCancelledAndDropsALateResult() {
        val executor = FakeExecutor()
        val gate = CompletableDeferred<PluginInvocationResult>()
        var cancelledInside = false
        executor.firstAnswer = { try { gate.await() } catch (e: CancellationException) { cancelledInside = true; throw e } }
        val c = controller(executor)
        c.press(call())
        assertTrue(c.ui.value.call is PluginCallState.Running)
        c.cancel()
        assertTrue("the running coroutine was cancelled", cancelledInside)
        assertEquals(PluginCallState.Cancelled, c.ui.value.call)
        gate.complete(PluginInvocationResult.Completed(PluginActionOutput("Echo", "late")))
        assertEquals("a late result must not redraw over Cancelled", PluginCallState.Cancelled, c.ui.value.call)
    }

    @Test fun closingTheScreenCancelsARunningCall() {
        val executor = FakeExecutor()
        val gate = CompletableDeferred<PluginInvocationResult>()
        var cancelledInside = false
        executor.firstAnswer = { try { gate.await() } catch (e: CancellationException) { cancelledInside = true; throw e } }
        val c = controller(executor)
        c.press(call())
        c.close()
        assertEquals(PluginCallState.Cancelled, c.ui.value.call)
        assertTrue("the running coroutine was cancelled", cancelledInside)
    }

    @Test fun onlyOneCallRunsAtATime() {
        val executor = FakeExecutor()
        executor.firstAnswer = { CompletableDeferred<PluginInvocationResult>().await() }
        val c = controller(executor)
        c.press(call())
        c.press(call())
        assertEquals(1, executor.invokes.size)
        assertEquals("Please wait", c.ui.value.notice?.title)
    }

    @Test fun aCallForAnotherPluginIsRefusedWithoutRunning() {
        val executor = FakeExecutor()
        val c = controller(executor)
        c.press(call(pluginId = "other.plugin"))
        assertTrue(executor.invokes.isEmpty())
        assertEquals("Blocked", c.ui.value.notice?.title)
        assertEquals(PluginCallState.Idle, c.ui.value.call)
    }

    @Test fun aHostGatedBubbleActionIsNotRunInTheApp() {
        val gated = BubbleAction.GatedHostAction(
            BubbleHostActionRequest("echo.plugin", "x", "commands", "d"),
            BubbleApproval("t", "m", null, "ok"), "blocked"
        ) { throw AssertionError("must not run in the app") }
        val c = controller(FakeExecutor())
        c.press(gated)
        assertEquals(InAppPluginController.GATED_NOTE, c.ui.value.notice?.content)
    }

    @Test fun aMessageActionAndANullActionAreHandled() {
        val c = controller(FakeExecutor())
        c.press(null)
        assertNull(c.ui.value.notice)
        c.press(BubbleAction.Message("Oops", "Enter some text."))
        assertEquals("Oops", c.ui.value.notice?.title)
        c.dismiss()
        assertNull(c.ui.value.notice)
    }

    @Test fun aPrepareFailureShowsTheUnavailableTitle() {
        val executor = FakeExecutor()
        val c = controller(executor, prepare = { Result.failure(IllegalStateException("not ready")) })
        c.press(call())
        val done = c.ui.value.call as PluginCallState.Done
        assertEquals("Echo unavailable", done.title)
        assertEquals(PluginCallOutcome.FAILED, done.outcome)
        assertTrue(executor.invokes.isEmpty())
    }

    @Test fun anUnexpectedExecutorErrorShowsAFixedMessageNotTheExceptionText() {
        val executor = FakeExecutor()
        executor.firstAnswer = { throw IllegalStateException("plugin said secret") }
        val c = controller(executor)
        c.press(call())
        assertEquals(PluginCallState.Done("Command failed", InAppPluginController.FAILED_NOTE, PluginCallOutcome.FAILED), c.ui.value.call)
    }

    @Test fun aNewCallIsAllowedAfterTheResultIsDismissed() {
        val executor = FakeExecutor()
        val c = controller(executor)
        c.press(call())
        c.dismiss()
        c.press(call())
        assertEquals(2, executor.invokes.size)
        assertNotNull(c.ui.value.call as? PluginCallState.Done)
    }

    @Test fun sourceGuardsTheControllerIsPureAndDependsOnEnabledNotBubbleAccess() {
        val dir = listOf("src/main/java/com/oneai/app/plugins", "app/src/main/java/com/oneai/app/plugins").map(::File).first { it.isDirectory }
        val source = File(dir, "InAppPluginController.kt").readText()
        assertFalse("no Android types", source.contains("import android."))
        listOf("hasBubbleAccess", "setBubbleAccess", "bubbleBindings", "PluginManager", "FloatingBubbleService").forEach {
            assertFalse("the controller must not touch $it", source.contains(it))
        }
        assertTrue("it refuses calls for another plugin", source.contains("call.pluginId != pluginId"))
    }
}
