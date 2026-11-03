package com.oneai.app.plugins

import com.oneai.app.core.PluginRecord
import com.oneai.app.core.PluginStore
import com.oneai.app.security.ActionAuditLogger
import com.oneai.app.security.PluginActionGateway
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginManagerTest {
    private class FakeStore : PluginStore {
        private val records = linkedMapOf<String, PluginRecord>()
        private var services = emptySet<String>()
        var developer = false
        val logs = mutableListOf<String>()
        private val gateway = PluginActionGateway({ id -> records[id] }, ActionAuditLogger { logs += it })
        private val runtime = PluginRuntime({ id -> records[id] }, gateway)
        override fun plugins() = records.values.toList()
        override fun savePlugin(plugin: PluginRecord) { records[plugin.id] = plugin }
        override fun removePlugin(id: String) { records.remove(id) }
        override fun bubbleServices() = services
        override fun setBubbleServices(services: Set<String>) { this.services = services }
        override fun developerMode() = developer
        override fun log(message: String) { logs += message }
        override fun pluginRuntime() = runtime
        override fun pluginActionGateway() = gateway
    }

    private fun template(id: String, deps: List<String> = emptyList(), bubble: String? = null) =
        PluginTemplate(id, "Plugin $id", "Test plugin", listOf("commands"), dependencies = deps, bubbleService = bubble)

    @Test fun coreWorksWithNoPluginsAndAnEmptyCatalogue() {
        val manager = PluginManager(FakeStore(), emptyList())
        assertTrue(manager.catalogue.isEmpty())
        assertTrue(manager.installed().isEmpty())
        assertTrue(manager.setEnabled("dev.example.none", true).isFailure)
        assertTrue(manager.remove("dev.example.none").isFailure)
    }

    @Test fun installedPluginsStartDisabledAndGetNoBubbleAccess() {
        val store = FakeStore()
        val t = template("dev.example.notes", bubble = "notes")
        val manager = PluginManager(store, listOf(t))
        assertTrue(manager.install(t).isSuccess)
        assertFalse(manager.installed().single().enabled)
        assertTrue(store.bubbleServices().isEmpty())
        assertTrue(manager.setEnabled(t.id, true).isSuccess)
        assertTrue("enabling must not grant Bubble access", store.bubbleServices().isEmpty())
    }

    @Test fun disablingAPluginRemovesItsBubbleServiceButKeepsOtherServices() {
        val store = FakeStore()
        val a = template("dev.example.a", bubble = "svc_a")
        val b = template("dev.example.b", bubble = "svc_b")
        val manager = PluginManager(store, listOf(a, b))
        manager.install(a); manager.install(b)
        manager.setEnabled(a.id, true); manager.setEnabled(b.id, true)
        store.setBubbleServices(setOf("svc_a", "svc_b"))
        manager.setEnabled(a.id, false)
        assertEquals(setOf("svc_b"), store.bubbleServices())
        assertTrue(manager.installed().first { it.id == a.id }.let { !it.enabled })
    }

    @Test fun removingAPluginRemovesItsRecordAndBubbleService() {
        val store = FakeStore()
        val a = template("dev.example.a", bubble = "svc_a")
        val manager = PluginManager(store, listOf(a))
        manager.install(a)
        store.setBubbleServices(setOf("svc_a"))
        assertTrue(manager.remove(a.id).isSuccess)
        assertTrue(manager.installed().isEmpty())
        assertTrue(store.bubbleServices().isEmpty())
    }

    @Test fun duplicateInstallAndMissingDependenciesAreRejected() {
        val manager = PluginManager(FakeStore(), emptyList())
        val base = template("dev.example.base")
        val dependent = template("dev.example.dep", deps = listOf("dev.example.base"))
        assertTrue(manager.install(dependent).isFailure)
        assertTrue(manager.install(base).isSuccess)
        assertTrue(manager.install(base).isFailure)
        assertTrue(manager.install(dependent).isSuccess)
    }

    @Test fun dependencyOrderIsEnforcedWhenEnablingDisablingAndRemoving() {
        val manager = PluginManager(FakeStore(), emptyList())
        val base = template("dev.example.base")
        val dependent = template("dev.example.dep", deps = listOf("dev.example.base"))
        manager.install(base); manager.install(dependent)
        assertTrue(manager.setEnabled(dependent.id, true).isFailure)
        assertTrue(manager.setEnabled(base.id, true).isSuccess)
        assertTrue(manager.setEnabled(dependent.id, true).isSuccess)
        assertTrue(manager.setEnabled(base.id, false).isFailure)
        assertTrue(manager.remove(base.id).isFailure)
    }

    @Test fun developerPluginCreationRequiresDeveloperMode() {
        val store = FakeStore()
        val manager = PluginManager(store, emptyList())
        assertTrue(manager.create("dev.example.mine", "Mine", "Mine", listOf("commands")).isFailure)
        store.developer = true
        assertTrue(manager.create("dev.example.mine", "Mine", "Mine", listOf("commands")).isSuccess)
        assertEquals(1, manager.installed().size)
    }
}
