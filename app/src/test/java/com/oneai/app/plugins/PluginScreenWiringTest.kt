package com.oneai.app.plugins

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Slice C2 (decision D12): source guards for the in-app plugin screen. They read source; they do not render
 * the screen, which has never been shown on an emulator or device.
 */
class PluginScreenWiringTest {
    private val dir = listOf("src/main/java/com/oneai/app/plugins", "app/src/main/java/com/oneai/app/plugins").map(::File).first { it.isDirectory }
    private val screen = File(dir, "PluginScreen.kt").readText()

    @Test fun theScreenNeverReadsBubbleAccessOrPluginManagement() {
        listOf("hasBubbleAccess", "setBubbleAccess", "bubbleBindings", "PluginManager", "FloatingBubbleService", "BubbleProviders").forEach {
            assertFalse("the in-app screen must not touch $it", screen.contains(it))
        }
    }

    @Test fun leavingTheScreenCancelsARunningCall() {
        val effect = screen.substringAfter("DisposableEffect(controller)").substringBefore("BackHandler")
        assertTrue(effect.contains("controller.close()"))
    }

    @Test fun aRunningCallShowsTheHostHeadingAndACancelButtonNeverSilence() {
        val running = screen.substringAfter("is PluginCallState.Running ->").substringBefore("is PluginCallState.Done")
        assertTrue(running.contains("call.heading"))
        assertTrue(running.contains("controller.cancel()"))
        assertTrue(running.contains("LEAVE_NOTE"))
    }

    @Test fun inputsAndButtonsAreDisabledWhileACallWaitsOrRuns() {
        assertTrue(screen.contains("val idle = ui.call !is PluginCallState.Running && ui.call !is PluginCallState.AwaitingApproval"))
        assertTrue(screen.contains("enabled = idle"))
    }

    @Test fun approvalIsAHostDialogThatApprovesOrRejectsThroughTheController() {
        val dialog = screen.substringAfter("ui.approval?.let").substringBefore("@Composable")
        assertTrue(dialog.contains("AlertDialog"))
        assertTrue(dialog.contains("controller.approve()"))
        assertTrue(dialog.contains("controller.reject()"))
        assertTrue(dialog.contains("approval.review"))
    }

    @Test fun pluginTextIsLabelledAsPluginText() {
        assertTrue(screen.contains("RESULT_NOTE"))
        assertTrue(screen.contains("SCREEN_NOTE"))
        assertTrue(screen.contains("supplied by the plugin"))
    }
}
