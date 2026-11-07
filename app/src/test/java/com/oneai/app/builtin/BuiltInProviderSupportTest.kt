package com.oneai.app.builtin

import com.oneai.app.bubble.BubbleHost
import com.oneai.app.bubble.BubbleServiceProvider
import com.oneai.app.bubble.BubbleView
import com.oneai.app.core.PluginRecord
import com.oneai.app.plugins.PluginActionInput
import com.oneai.app.plugins.PluginActionOutput
import com.oneai.app.plugins.PluginAdapter
import com.oneai.app.plugins.PluginAdapterRegistrar
import com.oneai.app.plugins.PluginManifest
import com.oneai.app.plugins.PluginRuntime
import com.oneai.app.security.ActionAuditLogger
import com.oneai.app.security.PluginActionGateway
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The runtime is shared by the whole process; these rules keep built-in adapters from outliving their Bubble. */
class BuiltInProviderSupportTest {
    private val manifest = PluginManifest(
        id = "dev.example.tools", name = "Example Tools", version = "1.0.0",
        description = "Test adapter", capabilities = listOf("commands")
    )
    private val record = PluginRecord(
        manifest.id, manifest.name, manifest.version, manifest.description,
        manifest.capabilities, true, manifestVersion = manifest.manifestVersion
    )

    private fun runtime(installed: PluginRecord? = record) =
        PluginRuntime({ installed }, PluginActionGateway({ installed }, ActionAuditLogger {}))

    private class StubAdapter(override val manifest: PluginManifest) : PluginAdapter {
        override suspend fun execute(input: PluginActionInput) = PluginActionOutput("Title", "Content")
    }

    private inner class InAppProvider(runtime: PluginAdapterRegistrar) : BuiltInBubbleProvider(runtime) {
        override val serviceId = "example"
        override val pluginId = manifest.id
        override val title = "Example"
        override fun view(host: BubbleHost) = BubbleView(emptyList())
    }

    @Test fun eachUseRegistersAFreshAdapterSoAStaleOneIsNeverReused() {
        val runtime = runtime()
        val built = mutableListOf<StubAdapter>()
        repeat(2) { assertTrue(ensureRegistered(runtime, manifest.id) { StubAdapter(manifest).also { built += it } }.isSuccess) }
        assertEquals("a new adapter per use, as when every call had its own runtime", 2, built.size)
        assertEquals(setOf(manifest.id), runtime.registeredPluginIds())
    }

    @Test fun aFailedRegistrationLeavesNothingRegistered() {
        val runtime = runtime(installed = null)
        assertTrue(ensureRegistered(runtime, manifest.id) { StubAdapter(manifest) }.isFailure)
        assertTrue(runtime.registeredPluginIds().isEmpty())
    }

    @Test fun builtInProvidersDropTheirAdapterWhenTheBubbleStops() {
        val runtime = runtime()
        ensureRegistered(runtime, manifest.id) { StubAdapter(manifest) }
        InAppProvider(runtime).releaseReady()
        assertTrue(runtime.registeredPluginIds().isEmpty())
    }

    @Test fun otherPluginsKeepTheirAdaptersWhenOneProviderReleases() {
        val other = manifest.copy(id = "dev.example.other")
        val otherRecord = record.copy(id = other.id)
        val runtime = PluginRuntime(
            { id -> listOf(record, otherRecord).firstOrNull { it.id == id } },
            PluginActionGateway({ id -> listOf(record, otherRecord).firstOrNull { it.id == id } }, ActionAuditLogger {})
        )
        assertTrue(runtime.register(StubAdapter(manifest)).isSuccess)
        assertTrue(runtime.register(StubAdapter(other)).isSuccess)
        InAppProvider(runtime).releaseReady()
        assertEquals(setOf(other.id), runtime.registeredPluginIds())
    }

    @Test fun theDefaultReleaseDoesNothingSoExternalPluginsStayRegistered() {
        val runtime = runtime()
        assertTrue(runtime.register(StubAdapter(manifest)).isSuccess)
        val plain = object : BubbleServiceProvider {
            override val serviceId = "example"
            override val pluginId = manifest.id
            override val title = "Example"
            override fun view(host: BubbleHost) = BubbleView(emptyList())
        }
        plain.releaseReady()
        assertFalse(runtime.registeredPluginIds().isEmpty())
    }
}
