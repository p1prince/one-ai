package com.oneai.app.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Decision D10 / D-R: the Core app declares no capability permissions (D8 slice 2), so Core code must not ask
 * Android for one on a plugin's behalf. A plugin is a separate signed app and requests its own permissions.
 */
class CoreRuntimePermissionGuardTest {
    private fun mainSources(): List<File> {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    @Test fun noCoreSourceRequestsTheRecordAudioPermission() {
        mainSources().forEach {
            assertFalse("${it.name} must not reference Manifest.permission.RECORD_AUDIO", it.readText().contains("Manifest.permission.RECORD_AUDIO"))
        }
    }

    @Test fun theActionButtonKeepsTheApprovalFlowAndHasNoPermissionLauncher() {
        val source = mainSources().first { it.name == "PluginActionButton.kt" }.readText()
        assertTrue(source.contains("SensitiveActionApprovalDialog"))
        assertTrue(source.contains("approveSensitiveAction"))
        assertFalse(source.contains("rememberLauncherForActivityResult"))
        assertFalse(source.contains("RequestPermission"))
        assertFalse(source.contains("checkSelfPermission"))
        assertFalse(source.contains("\"microphone\""))
    }

    @Test fun theMicrophoneGuidanceForPluginAuthorsIsStillPresent() {
        assertTrue(CapabilityPolicy.androidPermissionGuidance("microphone").contains("RECORD_AUDIO"))
    }
}
