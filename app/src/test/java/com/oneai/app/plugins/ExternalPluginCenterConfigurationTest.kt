package com.oneai.app.plugins

import com.oneai.app.commands.CommandRegistry
import com.oneai.app.core.PluginRecord
import com.oneai.app.core.PluginStore
import com.oneai.app.security.ActionAuditLogger
import com.oneai.app.security.PluginActionGateway
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 9.6b wiring: the enable-time block for missing required settings (D-C), reconciliation after an
 * update, and removal of stored configuration with the plugin.
 *
 * The trust store is seeded directly to keep these tests independent of the import flow (a manifest that
 * declares settings needs plugin API level 3, which the host provides since slice 9.6c).
 */
class ExternalPluginCenterConfigurationTest {
    private val cert = "a".repeat(64)
    private val pkg = "dev.example.notes"

    private class FakeStore : PluginStore {
        val records = linkedMapOf<String, PluginRecord>()
        val logs = mutableListOf<String>()
        private var services = emptySet<String>()
        override fun plugins() = records.values.toList()
        override fun savePlugin(plugin: PluginRecord) { records[plugin.id] = plugin }
        override fun removePlugin(id: String) { records.remove(id) }
        override fun bubbleServices() = services
        override fun setBubbleServices(services: Set<String>) { this.services = services }
        override fun developerMode() = false
        override fun log(message: String) { logs += message }
    }

    private class FakeSignatures : PackageSignatureSource {
        val signers = mutableMapOf<String, List<String>>()
        override fun currentSigners(packageName: String) = signers[packageName]
        override fun hasSigner(packageName: String, sha256: String) = signers[packageName]?.contains(sha256)
    }

    private val schema: PluginSettingsSchema = PluginSettingsValidator.validateSchema(
        PluginSettingsSchema(settings = listOf(
            PluginSettingDeclaration("repeat", PluginSettingType.NUMBER, "Repeat count", default = "1", min = 1, max = 5),
            PluginSettingDeclaration(
                "mode", PluginSettingType.CHOICE, "Mode", required = true,
                options = listOf(PluginSettingOption("plain", "Plain"), PluginSettingOption("fancy", "Fancy"))
            )
        ))
    ).getOrThrow()

    private fun manifest(versionCode: Long, withSettings: Boolean) = ExternalPluginManifest(
        packageName = pkg, versionName = "1.0.$versionCode", versionCode = versionCode, name = "Notes",
        description = "Quick notes",
        minCoreApi = if (withSettings) PluginApi.SETTINGS_MIN_API else 1,
        targetCoreApi = if (withSettings) PluginApi.SETTINGS_MIN_API else 1,
        capabilities = listOf("files"),
        commands = listOf(ExternalCommandDeclaration("add_note", "Add note", "Saves a note", "files")),
        settings = if (withSettings) schema else null
    )

    private inner class Fixture {
        val store = FakeStore()
        val configStore = FakePluginConfigStore()
        val signatures = FakeSignatures().also { it.signers[pkg] = listOf(cert) }
        val trustStore = InMemoryTrustStore().also { it.put(TrustEntry(pkg, cert, manifest(1, withSettings = true))) }
        val trust = PluginTrustRegistry(trustStore, signatures) { store.log(it) }
        val gateway = PluginActionGateway({ id -> store.plugins().firstOrNull { it.id == id } }, ActionAuditLogger { store.log(it) })
        val runtime = PluginRuntime({ id -> store.plugins().firstOrNull { it.id == id } }, gateway)
        val commands = CommandRegistry { id -> store.plugins().any { it.id == id && it.enabled } }
        var installed: ExternalPluginManifest? = manifest(1, withSettings = true)
        val registrar = ExternalPluginRegistrar(
            store, runtime, runtime, trust, commands, { _, _ -> error("these tests never bind to a plugin") }, emptySet(), 2_000
        )
        val configuration = PluginConfiguration(
            configStore,
            schemaOf = { id -> (trust.peek(id) as? TrustCheck.Trusted)?.entry?.trustedManifest?.settings },
            log = { store.log(it) }
        )
        lateinit var center: ExternalPluginCenter
        val manager = PluginManager(
            store, emptyList(), onPluginRemoved = { center.onRemoved(it) },
            bubbleBindings = { center.bubbleBindings() }, lifecycle = runtime, configStore = configStore
        )

        init {
            center = ExternalPluginCenter(
                discover = { listOfNotNull(installed?.let { DiscoveredPlugin(it.packageName, "Notes app", "svc", Result.success(it)) }) },
                trust = trust, registrar = registrar, manager = manager, configuration = configuration
            )
        }
    }

    @Test fun enablingIsBlockedUntilRequiredSettingsHaveValuesAndNothingIsRegisteredMeanwhile() {
        val f = Fixture()
        val blocked = f.center.setEnabled(pkg, true)
        assertTrue(blocked.isFailure)
        assertTrue("the message names what to set", blocked.exceptionOrNull()!!.message!!.contains("Mode"))
        assertTrue("no record was created or enabled", f.store.plugins().none { it.enabled })
        assertTrue("no command was registered", f.commands.list().none { it.owner == pkg })

        f.configuration.update(pkg, mapOf("mode" to "plain")).getOrThrow()
        assertTrue(f.center.setEnabled(pkg, true).isSuccess)
        assertTrue(f.store.plugins().single { it.id == pkg }.enabled)
    }

    @Test fun configuringARequiredSettingNeverEnablesThePluginOrGrantsBubbleAccess() {
        val f = Fixture()
        f.configuration.update(pkg, mapOf("mode" to "fancy")).getOrThrow()
        assertTrue("configuration is not authority", f.store.plugins().none { it.enabled })
        assertTrue(f.store.bubbleServices().isEmpty())
    }

    @Test fun aPluginWhoseSignerChangedHasNoConfigurableSettings() {
        val f = Fixture()
        f.signatures.signers[pkg] = listOf("b".repeat(64))
        assertTrue(f.configuration.update(pkg, mapOf("mode" to "plain")).isFailure)
        assertTrue(f.configStore.values(pkg).isEmpty())
    }

    @Test fun anAcceptedUpdateThatNoLongerDeclaresSettingsDropsTheStoredValues() {
        val f = Fixture()
        f.configuration.update(pkg, mapOf("mode" to "fancy", "repeat" to "3")).getOrThrow()
        assertFalse(f.configStore.values(pkg).isEmpty())

        f.installed = manifest(2, withSettings = false)
        f.center.checkForUpdates()

        assertTrue(f.configStore.values(pkg).isEmpty())
        assertTrue(f.store.logs.any { it.contains("Dropped settings of $pkg") })
        assertTrue("values never reach Activity", f.store.logs.none { it.contains("fancy") })
    }

    @Test fun removingAPluginRemovesItsStoredConfigurationAndLeavesOthersAlone() {
        val f = Fixture()
        val other = "dev.example.other"
        f.store.savePlugin(PluginRecord(pkg, "Notes", "1.0", "Quick notes", listOf("files"), false))
        f.store.savePlugin(PluginRecord(other, "Other", "1.0", "Other", listOf("files"), false))
        f.configStore.save(pkg, 1, mapOf("repeat" to "2"))
        f.configStore.save(other, 1, mapOf("repeat" to "4"))

        f.manager.remove(pkg).getOrThrow()

        assertFalse(f.configStore.hasEntry(pkg))
        assertEquals(mapOf("repeat" to "4"), f.configStore.values(other))
    }

    @Test fun aCenterWithoutConfigurationAppliesNoConfigurationRules() {
        val f = Fixture()
        val plain = ExternalPluginCenter(
            discover = { emptyList() }, trust = f.trust, registrar = f.registrar, manager = f.manager
        )
        assertTrue("without a configuration object the center does not gate enabling", plain.setEnabled(pkg, true).isSuccess)
    }
}
