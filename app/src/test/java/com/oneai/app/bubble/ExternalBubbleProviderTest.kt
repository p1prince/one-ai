package com.oneai.app.bubble

import android.content.Context
import com.oneai.app.plugins.ExternalBubbleDeclaration
import com.oneai.app.plugins.ExternalCommandDeclaration
import com.oneai.app.plugins.ExternalPluginManifest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalBubbleProviderTest {
    private val manifest = ExternalPluginManifest(
        packageName = "dev.example.notes", versionName = "1", versionCode = 1, name = "Notes", minCoreApi = 1, targetCoreApi = 1,
        capabilities = listOf("files"),
        commands = listOf(ExternalCommandDeclaration("add_note", "Add note", "", "files")),
        bubble = ExternalBubbleDeclaration("notes", "My Notes")
    )
    private val host = object : BubbleHost {
        override val context: Context get() = throw UnsupportedOperationException("no Android in unit tests")
        override fun hideBubble() {}
    }
    private val json = """{"v":1,"elements":[{"t":"label","text":"Hi"},{"t":"input","key":"text","hint":"Note"},
        {"t":"button","label":"Save","command":"add_note","args":{"text":"@text"}}]}"""

    private fun provider(source: String?) = ExternalBubbleProvider(manifest) { source }

    @Test fun identityComesFromTheManifestAndIsAsync() {
        val p = provider(json)
        assertEquals("notes", p.serviceId); assertEquals("dev.example.notes", p.pluginId); assertEquals("My Notes", p.title)
        assertTrue(p.isAsync)
        assertTrue(BubbleProviderRegistry().register(p).isSuccess)
    }

    @Test fun loadsAndMapsElements() = runBlocking {
        val elements = provider(json).loadView(host).elements
        assertEquals(BubbleElement.Label("Hi"), elements[0])
        assertEquals(BubbleElement.TextInput("text", "Note"), elements[1])
        assertEquals("Save", (elements[2] as BubbleElement.Button).label)
    }

    @Test fun buttonBuildsAnApprovedPluginCallShowingExactArguments() = runBlocking {
        val button = provider(json).loadView(host).elements[2] as BubbleElement.Button
        val call = button.onPress(mapOf("text" to "buy milk")) as BubbleAction.PluginCall
        assertEquals("dev.example.notes", call.pluginId)
        assertEquals("add_note", call.input.actionId)
        assertEquals("files", call.input.capability)
        assertEquals(mapOf("text" to "buy milk"), call.input.values)
        assertEquals("text: buy milk", call.approval.review)
        assertEquals("notes", call.backTo)
        assertEquals("Notes blocked", call.outcomes.blockedTitle)
    }

    @Test fun invalidOrMissingViewFailsInsteadOfRendering() {
        assertTrue(runCatching { runBlocking { provider(null).loadView(host) } }.isFailure)
        assertTrue(runCatching { runBlocking { provider("""{"v":1,"elements":[{"t":"button","label":"x","command":"nope"}]}""").loadView(host) } }.isFailure)
    }

    @Test fun registryCanDropAPluginsService() {
        val r = BubbleProviderRegistry()
        r.register(provider(json))
        assertTrue(r.unregisterPlugin("dev.example.notes"))
        assertNull(r.provider("notes"))
        assertTrue(!r.unregisterPlugin("dev.example.notes"))
    }
}
