package com.oneai.app.bubble

import com.oneai.app.plugins.PluginActionOutput
import com.oneai.app.core.PluginInvocationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BubbleContractTest {
    private class FakeProvider(
        override val serviceId: String,
        override val pluginId: String,
        override val title: String = "Fake"
    ) : BubbleServiceProvider {
        override fun view(host: BubbleHost) = BubbleView(listOf(BubbleElement.Label("hi")))
    }

    private val outcomes = BubbleOutcomes("X unavailable", "X blocked", "X failed")

    @Test fun outcomesMapEveryPluginResult() {
        assertEquals(BubbleMessage("Done", "ok"), outcomes.present(PluginInvocationResult.Completed(PluginActionOutput("Done", "ok"))))
        assertEquals(BubbleMessage("X blocked", "no"), outcomes.present(PluginInvocationResult.Rejected("no")))
        assertEquals(BubbleMessage("X failed", "bad"), outcomes.present(PluginInvocationResult.Failed("bad")))
        assertEquals("Approval expired", outcomes.present(PluginInvocationResult.AwaitingApproval("again"))!!.title)
    }

    @Test fun successTitleCanRetitleOrSuppressAResult() {
        val custom = outcomes.copy(successTitle = { if (it.title == "Settings opened") null else "Custom" })
        assertNull(custom.present(PluginInvocationResult.Completed(PluginActionOutput("Settings opened", "x"))))
        assertEquals("Custom", custom.present(PluginInvocationResult.Completed(PluginActionOutput("Other", "x")))!!.title)
    }

    @Test fun registryStartsEmptyAndHasNoBindings() {
        val r = BubbleProviderRegistry()
        assertTrue(r.providers().isEmpty()); assertTrue(r.bindings().isEmpty())
    }

    @Test fun registeredProvidersProduceBindingsAndAreFoundById() {
        val r = BubbleProviderRegistry()
        assertTrue(r.register(FakeProvider("notes", "dev.example.notes")).isSuccess)
        assertEquals(mapOf("dev.example.notes" to "notes"), r.bindings())
        assertEquals("notes", r.provider("notes")!!.serviceId)
        assertNull(r.provider("missing"))
    }

    @Test fun registryRejectsReservedDuplicateAndMalformedProviders() {
        val r = BubbleProviderRegistry()
        assertTrue(r.register(FakeProvider(BubbleUiPolicy.COMMAND_MANAGER, "dev.example.a")).isFailure)
        assertTrue(r.register(FakeProvider("Bad Id", "dev.example.a")).isFailure)
        assertTrue(r.register(FakeProvider("ok", " ")).isFailure)
        assertTrue(r.register(FakeProvider("ok", "dev.example.a")).isSuccess)
        assertTrue(r.register(FakeProvider("ok", "dev.example.b")).isFailure)
        assertTrue(r.register(FakeProvider("other", "dev.example.a")).isFailure)
        assertEquals(1, r.providers().size)
    }

    @Test fun bubbleBindingsFeedTheExistingAvailabilityPolicyWithNoCoreChange() {
        val r = BubbleProviderRegistry()
        r.register(FakeProvider("notes", "dev.example.notes"))
        assertEquals(setOf("command_manager"), BubbleUiPolicy.availableServices(emptySet(), r.bindings()))
        assertEquals(setOf("command_manager", "notes"), BubbleUiPolicy.availableServices(setOf("dev.example.notes"), r.bindings()))
    }

    /** Guards the architecture rule: the Bubble shell must not reference any specific capability. */
    @Test fun bubbleShellContainsNoCapabilitySpecificCode() {
        val forbidden = Regex(
            "VoiceInterface|WebResearch|WebSearch|ScreenIntelligence|ScreenCapture|AppControl|DeviceControl|AutoDroid|" +
                "BuiltInCatalogue|BraveSearch|OpenAiCompatibleVision|TextToSpeech|SpeechRecogni|MediaProjection|" +
                "one\\.(web|voice|screen|app|device|automation)\\.|\"(web_research|voice_interface|screen_intelligence|app_control|device_control|automation)\""
        )
        val dir = listOf("src/main/java/com/oneai/app/bubble", "app/src/main/java/com/oneai/app/bubble").map(::File).first { it.isDirectory }
        val shell = listOf("FloatingBubbleService.kt", "BubbleContract.kt", "BubbleUiPolicy.kt", "BubbleResultHandoff.kt")
        shell.forEach { name ->
            val text = File(dir, name).readText()
            val hit = forbidden.find(text)
            assertTrue("$name references a specific capability: ${hit?.value}", hit == null)
        }
    }
}
