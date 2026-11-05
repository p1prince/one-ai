package com.oneai.app.plugins

import com.oneai.app.PluginHostComposition
import com.oneai.app.core.PluginActionExecutor
import com.oneai.app.core.PluginActionInput
import com.oneai.app.core.PluginInvocationResult
import com.oneai.app.core.PluginLifecycleController
import com.oneai.app.core.PluginRecord
import com.oneai.app.core.PluginStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the exact production host-composition class with no capability modules installed. */
class PluginExecutionServicesTest {
    private class EmptyStore : PluginStore {
        private val records = linkedMapOf<String, PluginRecord>()
        private val services = linkedSetOf<String>()
        val events = mutableListOf<String>()
        override fun plugins() = records.values.toList()
        override fun savePlugin(plugin: PluginRecord) { records[plugin.id] = plugin }
        override fun removePlugin(id: String) { records.remove(id) }
        override fun bubbleServices() = services.toSet()
        override fun setBubbleServices(services: Set<String>) { this.services.clear(); this.services.addAll(services) }
        override fun developerMode() = false
        override fun log(message: String) { events += message }
    }

    @Test fun productionCompositionStartsWithNoInstalledPluginsAdaptersOrBubbleProviders() {
        val store = EmptyStore()
        val host = PluginHostComposition(store, emptyList())
        assertTrue(host.manager.catalogue.isEmpty())
        assertTrue(host.manager.installed().isEmpty())
        assertTrue(host.execution.runtime.registeredPluginIds().isEmpty())
        assertTrue(host.bubbleProviders.providers().isEmpty())
        assertTrue(host.bubbleProviders.bindings().isEmpty())
        assertTrue(store.bubbleServices().isEmpty())
    }

    @Test fun sharedExecutionOwnerRejectsCallsWhenNoPluginIsInstalledOrRegistered() = runBlocking {
        val host = PluginHostComposition(EmptyStore(), emptyList())
        val result = host.execution.runtime.invoke(
            "dev.example.missing", PluginActionInput("run", "commands", "Run a command")
        )
        assertTrue(result is PluginInvocationResult.Rejected)
        assertEquals(emptySet<String>(), host.execution.runtime.registeredPluginIds())
    }

    @Test fun executionAndLifecycleContractsAreExposedWithoutLeakingIntoStorage() {
        val store = EmptyStore()
        val host = PluginHostComposition(store, emptyList())
        assertTrue(host.execution.runtime is PluginActionExecutor)
        assertTrue(host.execution.runtime is PluginLifecycleController)
        assertTrue(store.plugins().isEmpty())
        assertTrue(store.events.isEmpty())
    }

    @Test fun productionManagerAndBubbleShareOneRuntimeForPluginLifecycle() {
        val id = "dev.example.lifecycle"
        val template = PluginTemplate(id, "Lifecycle", "Test plugin", listOf("commands"))
        val store = EmptyStore()
        val host = PluginHostComposition(store, listOf(template))
        assertTrue(host.manager.install(template).isSuccess)
        assertTrue(host.manager.setEnabled(id, true).isSuccess)

        val record = host.manager.installed().single()
        val manifest = PluginManifest(
            id = record.id,
            name = record.name,
            version = record.version,
            description = record.description,
            capabilities = record.capabilities,
            dependencies = record.dependencies
        )
        val adapter = object : PluginAdapter {
            override val manifest = manifest
            override suspend fun execute(input: PluginActionInput) =
                PluginActionOutput("Result", "Executed")
        }
        assertTrue(host.execution.runtime.register(adapter).isSuccess)
        assertEquals(setOf(id), host.execution.runtime.registeredPluginIds())

        assertTrue(host.manager.setEnabled(id, false).isSuccess)
        assertEquals(emptySet<String>(), host.execution.runtime.registeredPluginIds())
    }

    @Test fun pluginManagerUsesTheInjectedLifecycleBoundaryForDisableAndRemoval() {
        val store = EmptyStore()
        val removed = mutableListOf<String>()
        var revocations = 0
        val lifecycle = object : PluginLifecycleController {
            override fun unregister(pluginId: String): Boolean { removed += pluginId; return true }
            override fun revokePendingApprovals() { revocations++ }
        }
        val template = PluginTemplate("dev.example.lifecycle", "Lifecycle", "Test", listOf("commands"))
        val manager = PluginManager(store, listOf(template), lifecycle = lifecycle)
        assertTrue(manager.install(template).isSuccess)
        assertTrue(manager.setEnabled(template.id, true).isSuccess)
        assertTrue(manager.setEnabled(template.id, false).isSuccess)
        assertTrue(manager.remove(template.id).isSuccess)
        assertEquals(listOf(template.id, template.id), removed)
        assertEquals(2, revocations)
    }
}
