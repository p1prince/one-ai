package com.oneai.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * D8 slice 4 guard, landed with slice 2 (decision D9): the Core manifest and Core sources carry no
 * capability permissions, components or classes. Capability entries live only in `src/capabilities`.
 */
class CoreManifestBoundaryTest {
    private fun file(relative: String): File =
        listOf(File("src/$relative"), File("app/src/$relative")).firstOrNull { it.isFile }
            ?: error("Could not locate $relative")

    private fun mainSources(): List<File> {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    @Test fun coreManifestDeclaresNoCapabilityPermissionsOrComponents() {
        val manifest = file("main/AndroidManifest.xml").readText()
        listOf(
            "RECORD_AUDIO", "MEDIA_PROJECTION", "mediaProjection", "ScreenCapture", "VoiceCapture",
            "ScreenRegion", "ScreenProjection", "autodroid"
        ).forEach { assertFalse("Core manifest must not mention $it", manifest.contains(it, ignoreCase = true)) }
    }

    @Test fun coreManifestKeepsTheCoreBubbleDeclarations() {
        val manifest = file("main/AndroidManifest.xml").readText()
        listOf("INTERNET", "SYSTEM_ALERT_WINDOW", "FOREGROUND_SERVICE_SPECIAL_USE", "POST_NOTIFICATIONS", ".bubble.FloatingBubbleService", ".MainActivity")
            .forEach { assertTrue("Core manifest must keep $it", manifest.contains(it)) }
    }

    @Test fun capabilityEntriesLiveOnlyInTheCapabilitiesFlavorManifest() {
        val flavor = file("capabilities/AndroidManifest.xml").readText()
        listOf("RECORD_AUDIO", "FOREGROUND_SERVICE_MEDIA_PROJECTION", ".bubble.ScreenProjectionCaptureService", ".bubble.VoiceCaptureActivity")
            .forEach { assertTrue("Capabilities manifest must hold $it", flavor.contains(it)) }
    }

    @Test fun coreSourcesDoNotReferenceCapabilityClasses() {
        val forbidden = Regex(
            "BuiltInCatalogue|BuiltInBubbleProviders|BraveSearch|WebResearchAdapter|ScreenIntelligenceAdapter|VoiceInterfaceAdapter|" +
                "AutoDroidAdapter|ScreenCaptureConsent|ScreenCaptureArtifact|ScreenProjectionCapture|ScreenRegion|VoiceCaptureActivity|" +
                "OpenAiCompatibleVision|WebSearchPolicy|WebSearchProvider"
        )
        mainSources().forEach { source ->
            val hit = forbidden.find(source.readText())
            assertTrue("${source.name} references capability code: ${hit?.value}", hit == null)
        }
    }

    @Test fun settingsDoesNotMentionAnAbsentCapability() {
        val main = listOf(File("src/main/java/com/oneai/app/MainActivity.kt"), File("app/src/main/java/com/oneai/app/MainActivity.kt"))
            .first { it.isFile }.readText()
        assertFalse(main.contains("Screen Intelligence"))
        assertFalse(main.contains("Web Research"))
    }
}
