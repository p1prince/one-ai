package com.oneai.app.plugins

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Slice C5 (decision D12): the documents describe in-app plugin use as the code does, and the emulator rows exist
 * and are marked as needing a variant where one is required. These read files; they do not run anything on a device.
 */
class InAppUseDocsTest {
    private fun repoFile(relative: String): File =
        listOf(File("../$relative"), File(relative), File("../../$relative")).firstOrNull { it.isFile }
            ?: error("Could not locate $relative")

    private fun source(relative: String): String =
        listOf("src/main/java/com/oneai/app/", "app/src/main/java/com/oneai/app/").map { File(it + relative) }.first { it.isFile }.readText()

    private val sdk get() = repoFile("PLUGIN_SDK.md").readText()
    private val guide get() = repoFile("docs/PLUGIN_DEVELOPER_GUIDE.md").readText()
    private val plan get() = repoFile("docs/EMULATOR_TEST_PLAN.md").readText()

    @Test fun theSdkDescribesInAppUseAndStatesBubbleAccessIsNotConsulted() {
        val section = sdk.substringAfter("## In-app plugin screens (decision D12)").substringBefore("\n## ")
        assertTrue(section.isNotBlank())
        listOf("**Enabled**", "never consulted", "sub-page", "Allow once", "Reject", "**Running**", "**Cancel**", "API 2").forEach {
            assertTrue("the SDK section must mention $it", section.contains(it))
        }
        assertTrue("no API change is claimed", section.contains("No SDK or plugin API change"))
    }

    @Test fun theDeveloperGuideTellsPluginAuthorsInAppUseNeedsNoBubbleAccess() {
        val section = guide.substringAfter("## 6. Bubble views (declarative)").substringBefore("\n## ")
        assertTrue(section.contains("**not** Bubble access"))
        assertTrue(section.contains("Plugin screen request"))
        assertTrue("the original two-settings rule is kept", section.contains("Two separate user settings apply"))
    }

    @Test fun everyEmulatorRowFromM41ToM49ExistsOnceAndNeedingARowIsMarkedAsAVariant() {
        (41..49).forEach { n ->
            assertEquals("M$n must appear exactly once as a row", 1, Regex("(?m)^\\| M$n \\|").findAll(plan).count())
        }
        val needsVariant = plan.lines().first { it.startsWith("M13, M14") }
        listOf("M46", "M48", "M49").forEach { assertTrue("$it needs a variant", needsVariant.contains(it)) }
    }

    @Test fun theDocumentedLabelsMatchTheCode() {
        val screen = source("plugins/PluginScreen.kt")
        val launch = screen.substringAfter("launchPrompt?.let").substringBefore("ui.approval?.let")
        assertTrue(launch.contains("Text(\"Allow once\")"))
        assertTrue(launch.contains("Text(\"Reject\")"))
        assertTrue(source("plugins/PluginLaunchApprovalController.kt").contains("PLUGIN SCREEN REQUEST"))
        val main = source("MainActivity.kt")
        assertTrue(main.contains("Text(\"Open\",color=Gold)"))
        assertTrue(main.contains("YOUR PLUGINS"))
    }

    @Test fun theDocumentsClaimNoDeviceResult() {
        listOf(sdk, guide).forEach { doc ->
            val section = doc.lines().filter { it.contains("in-app", ignoreCase = true) || it.contains("inside ONE AI") }.joinToString("\n")
            assertFalse(section.contains("verified on a device"))
            assertFalse(section.contains("tested on a device"))
        }
    }
}
