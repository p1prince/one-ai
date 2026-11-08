package com.oneai.app.plugins

import com.oneai.app.core.PluginActionExecutor
import com.oneai.app.core.PluginActionInput
import com.oneai.app.core.PluginInvocationResult
import com.oneai.app.bubble.BubbleElement
import com.oneai.app.bubble.BubbleView
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Slice C4 (decision D12 / D-AB): the in-app presenter for a plugin-requested screen and the rule that routes a
 * request to it. Source guards read source; nothing here renders a screen or sends a PendingIntent, and neither has
 * been exercised on an emulator or device.
 */
class InAppLaunchPresenterTest {
    private class Spy(val answer: String = "The approved plugin screen launch was sent.") {
        var approved = 0
        var rejected = 0
        val approve: () -> String = { approved++; answer }
        val reject: () -> Unit = { rejected++ }
    }

    private fun show(p: InAppLaunchPresenter, spy: Spy, details: String = "Package: dev.echo\nRequested screen: Settings") =
        p.showApproval("PLUGIN SCREEN REQUEST", details, "This description comes from the plugin.", spy.approve, spy.reject)

    @Test fun aRequestShowsAPromptAndNothingIsSentUntilTheUserConfirms() {
        val p = InAppLaunchPresenter()
        val spy = Spy()
        assertNull(p.prompt.value)
        show(p, spy)
        assertEquals("PLUGIN SCREEN REQUEST", p.prompt.value?.title)
        assertEquals(0, spy.approved)
        assertEquals(0, spy.rejected)
    }

    @Test fun confirmingRunsTheHostApproveActionExactlyOnceAndShowsItsOutcome() {
        val p = InAppLaunchPresenter()
        val spy = Spy()
        show(p, spy)
        p.approve()
        p.approve()
        assertEquals(1, spy.approved)
        assertEquals(0, spy.rejected)
        assertNull(p.prompt.value)
        assertEquals(InAppLaunchPresenter.RESULT_TITLE, p.result.value?.title)
        assertEquals("The approved plugin screen launch was sent.", p.result.value?.message)
    }

    @Test fun rejectingRunsTheHostRejectActionOnceAndNeverApproves() {
        val p = InAppLaunchPresenter()
        val spy = Spy()
        show(p, spy)
        p.reject()
        p.reject()
        p.approve()
        assertEquals(1, spy.rejected)
        assertEquals(0, spy.approved)
        assertNull(p.prompt.value)
        assertNull(p.result.value)
    }

    @Test fun anApproveActionThatThrowsShowsAFixedMessageNotTheExceptionText() {
        val p = InAppLaunchPresenter()
        p.showApproval("T", "d", "w", { throw IllegalStateException("secret detail") }, {})
        p.approve()
        assertEquals(InAppLaunchPresenter.FAILED, p.result.value?.message)
        assertFalse(p.result.value?.message.orEmpty().contains("secret"))
    }

    @Test fun closingRejectsAnUnansweredRequestAndClearsEverything() {
        val p = InAppLaunchPresenter()
        val spy = Spy()
        show(p, spy)
        p.close()
        assertEquals(1, spy.rejected)
        assertEquals(0, spy.approved)
        assertNull(p.prompt.value)
        assertNull(p.result.value)
    }

    @Test fun closingWithNothingWaitingDoesNothing() {
        val p = InAppLaunchPresenter()
        p.close()
        assertNull(p.prompt.value)
        assertNull(p.result.value)
    }

    @Test fun aNewerRequestRejectsTheUnansweredOneSoTheBrokerIsNeverLeftHoldingIt() {
        val p = InAppLaunchPresenter()
        val first = Spy()
        val second = Spy()
        show(p, first)
        show(p, second)
        assertEquals(1, first.rejected)
        p.approve()
        assertEquals(0, first.approved)
        assertEquals(1, second.approved)
    }

    @Test fun pluginSuppliedTextIsCleanedAndBounded() {
        val p = InAppLaunchPresenter()
        val long = "x".repeat(500)
        show(p, Spy(), details = "Package: dev.echo\nRequested screen: Pay‮me\u0000now $long")
        val details = p.prompt.value!!.details
        val lines = details.split('\n')
        assertEquals(2, lines.size)
        assertTrue(lines.all { it.length <= 200 })
        assertFalse(details.any { it == '‮' || Character.isISOControl(it) && it != '\n' })
    }

    @Test fun showResultClearsThePromptAndShowsTheMessage() {
        val p = InAppLaunchPresenter()
        show(p, Spy())
        p.showResult("Done", "It worked")
        assertNull(p.prompt.value)
        assertEquals(InAppLaunchResult("Done", "It worked"), p.result.value)
        p.dismissResult()
        assertNull(p.result.value)
    }

    // ---- routing ----

    @Test fun theOpenPluginsRequestGoesInAppAndEveryOtherRequestKeepsGoingToTheBubble() {
        val bubble = Any()
        val inApp = Any()
        assertSame(inApp, PluginLaunchRouting.select("dev.echo", bubble, inApp, "dev.echo"))
        assertSame("another plugin's request is not shown over this screen", bubble, PluginLaunchRouting.select("dev.other", bubble, inApp, "dev.echo"))
        assertSame("no in-app screen open", bubble, PluginLaunchRouting.select("dev.echo", bubble, null, null))
        assertSame("in-app surface without a plugin id is never chosen", bubble, PluginLaunchRouting.select("dev.echo", bubble, inApp, null))
    }

    @Test fun withNoSurfaceAttachedARequestStaysQueued() {
        assertNull(PluginLaunchRouting.select<Any>("dev.echo", null, null, null))
        assertNull(PluginLaunchRouting.select("dev.other", null, Any(), "dev.echo"))
        val inApp = Any()
        assertSame(inApp, PluginLaunchRouting.select("dev.echo", null, inApp, "dev.echo"))
    }

    // ---- controller hooks ----

    private val jobs = mutableListOf<Job>()
    private val executor = object : PluginActionExecutor {
        override suspend fun invoke(pluginId: String, input: PluginActionInput, approvalTicket: String?): PluginInvocationResult =
            PluginInvocationResult.Failed("unused")
        override fun approveSensitiveAction(pluginId: String, input: PluginActionInput): Result<String> = Result.failure(IllegalStateException())
    }

    private fun controller(opened: MutableList<InAppLaunchPresenter>, closed: IntArray): InAppPluginController {
        val job = Job().also { jobs += it }
        return InAppPluginController(
            "dev.echo", CoroutineScope(Dispatchers.Unconfined + job), executor,
            loadView = { BubbleView(listOf(BubbleElement.Label("x"))) }, prepare = { Result.success(Unit) },
            onOpened = { opened += it }, onClosed = { closed[0]++ }
        )
    }

    @Test fun openingRoutesThisPluginsRequestsHereOnceAndClosingStopsItOnce() {
        val opened = mutableListOf<InAppLaunchPresenter>()
        val closed = intArrayOf(0)
        val c = controller(opened, closed)
        c.opened()
        c.opened()
        assertEquals(1, opened.size)
        assertSame(c.launch, opened.single())
        c.close()
        c.close()
        assertEquals(1, closed[0])
    }

    @Test fun closingWithoutEverOpeningNeverDetaches() {
        val closed = intArrayOf(0)
        controller(mutableListOf(), closed).close()
        assertEquals(0, closed[0])
    }

    @Test fun closingTheControllerRejectsAnUnansweredScreenRequest() {
        val c = controller(mutableListOf(), intArrayOf(0))
        val spy = Spy()
        c.opened()
        c.launch.showApproval("T", "d", "w", spy.approve, spy.reject)
        c.close()
        assertEquals(1, spy.rejected)
        assertEquals(0, spy.approved)
        assertNull(c.launch.prompt.value)
    }

    // ---- source guards ----

    private fun source(relative: String): String =
        listOf("src/main/java/com/oneai/app/", "app/src/main/java/com/oneai/app/").map { File(it + relative) }.first { it.isFile }.readText()

    @Test fun thePresenterIsPureAndSendsNothingItself() {
        val presenter = source("plugins/InAppLaunchPresenter.kt")
        assertFalse(presenter.contains("import android"))
        listOf("PendingIntent", "ActivityOptions", "hasBubbleAccess", "PluginManager", "FloatingBubbleService").forEach {
            assertFalse("the presenter must not touch $it", presenter.contains(it))
        }
    }

    @Test fun theHostLaunchPolicyStaysInOnePlaceAndTheInAppPathReusesIt() {
        val controller = source("plugins/PluginLaunchApprovalController.kt")
        assertTrue("the host controller is the place that sends", controller.contains("pendingIntent.send("))
        listOf("PendingIntent", "ActivityOptions").forEach {
            assertFalse("the screen and presenter must not touch $it", source("plugins/PluginScreen.kt").contains(it))
        }
        val inAppAttach = controller.substringAfter("fun attachInApp(").substringBefore("fun detachInApp(")
        assertTrue("the same observer (and so the same approve path) is used", inAppAttach.contains("broker.setObserver(observer)"))
        assertTrue(controller.contains("PluginLaunchRouting.select("))
    }

    @Test fun theScreenDrawsTheLaunchApprovalWithOnlyConfirmAndRejectActions() {
        val screen = source("plugins/PluginScreen.kt")
        val dialog = screen.substringAfter("launchPrompt?.let").substringBefore("ui.approval?.let")
        assertTrue(dialog.contains("AlertDialog"))
        assertTrue(dialog.contains("controller.launch.approve()"))
        assertTrue(dialog.contains("controller.launch.reject()"))
        assertTrue(dialog.contains("prompt.warning"))
        assertTrue("opening routes requests here", screen.contains("controller.opened()"))
    }

    @Test fun productionAttachesAndDetachesThroughTheLaunchController() {
        val services = source("AppServices.kt")
        val factory = services.substringAfter("fun inAppPluginController(").substringBefore("fun restorePlugins")
        assertTrue(factory.contains("pluginLaunchApprovalController.attachInApp(pluginId, presenter)"))
        assertTrue(factory.contains("pluginLaunchApprovalController.detachInApp()"))
    }
}
