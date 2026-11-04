package com.oneai.app.plugins

import com.oneai.app.commands.CommandRegistry
import com.oneai.app.core.PluginRecord
import com.oneai.app.core.PluginStore
import com.oneai.app.security.ActionAuditLogger
import com.oneai.app.security.PluginActionGateway
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Discovery, import, enabling, updates and removal of plugin APKs, with fakes for Android (no device here). */
class ExternalPluginCenterTest {
    private val cert = "a".repeat(64)
    private val pkg = "dev.example.notes"

    private class FakeStore : PluginStore {
        val records = linkedMapOf<String, PluginRecord>()
        val logs = mutableListOf<String>()
        private var services = emptySet<String>()
        private val gateway = PluginActionGateway({ id -> records[id] }, ActionAuditLogger { logs += it })
        private val runtime = PluginRuntime({ id -> records[id] }, gateway)
        override fun plugins() = records.values.toList()
        override fun savePlugin(plugin: PluginRecord) { records[plugin.id] = plugin }
        override fun removePlugin(id: String) { records.remove(id) }
        override fun bubbleServices() = services
        override fun setBubbleServices(services: Set<String>) { this.services = services }
        override fun developerMode() = false
        override fun log(message: String) { logs += message }
        override fun pluginRuntime() = runtime
        override fun pluginActionGateway() = gateway
    }

    private class FakeSignatures : PackageSignatureSource {
        val signers = mutableMapOf<String, List<String>>()
        override fun currentSigners(packageName: String) = signers[packageName]
        override fun hasSigner(packageName: String, sha256: String) = signers[packageName]?.contains(sha256)
    }

    private fun manifest(versionCode: Long = 1, extraCommand: Boolean = false): ExternalPluginManifest {
        val commands = mutableListOf(ExternalCommandDeclaration("add_note", "Add note", "Saves a note", "files"))
        if (extraCommand) commands += ExternalCommandDeclaration("find_note", "Find note", "", "files")
        return ExternalPluginManifest(
            packageName = pkg, versionName = "1.0.$versionCode", versionCode = versionCode, name = "Notes",
            description = "Quick notes", minCoreApi = 1, targetCoreApi = 1, capabilities = listOf("files"),
            commands = commands, bubble = ExternalBubbleDeclaration("notes", "Notes")
        )
    }

    private inner class Fixture(reserved: Set<String> = emptySet(), reservedServices: Set<String> = emptySet()) {
        val store = FakeStore()
        val signatures = FakeSignatures().also { it.signers[pkg] = listOf(cert) }
        val trust = PluginTrustRegistry(InMemoryTrustStore(), signatures) { store.log(it) }
        val commands = CommandRegistry { id -> store.plugins().any { it.id == id && it.enabled } }
        val bubbleServices = mutableSetOf<String>()
        /** What is installed on the "device"; null means the plugin app is not installed. */
        var installed: ExternalPluginManifest? = manifest()
        var extras: List<DiscoveredPlugin> = emptyList()
        val registrar = ExternalPluginRegistrar(
            store, trust, commands, { _, _ -> error("these tests never bind to a plugin") }, reserved, 2_000,
            onBubbleService = { m, _ -> bubbleServices += m.id },
            onBubbleRemoved = { bubbleServices -= it }
        )
        lateinit var center: ExternalPluginCenter
        val manager = PluginManager(store, emptyList(), onPluginRemoved = { center.onRemoved(it) }, bubbleBindings = { center.bubbleBindings() })

        init {
            center = ExternalPluginCenter(
                discover = { listOfNotNull(installed?.let { DiscoveredPlugin(it.packageName, "Notes app", "svc", Result.success(it)) }) + extras },
                trust = trust, registrar = registrar, manager = manager, reservedBubbleServices = reservedServices
            )
        }

        fun import(versionCode: Long = 1, fingerprint: String = cert) = center.import(pkg, versionCode, fingerprint)
        fun blockedEvents() = store.logs.count { it.startsWith("Blocked ") }
    }

    @Test fun aPluginFoundOnTheDeviceIsListedAsNotImportedAndNothingIsTrusted() {
        val f = Fixture()
        f.extras = listOf(DiscoveredPlugin("dev.example.broken", "Broken", "svc", Result.failure(IllegalArgumentException("bad manifest"))))
        val snapshot = f.center.snapshot()
        assertEquals(setOf(pkg, "dev.example.broken"), snapshot.candidates.map { it.plugin.packageName }.toSet())
        assertTrue(snapshot.candidates.none { it.imported })
        assertTrue(snapshot.candidates.first { it.plugin.packageName == "dev.example.broken" }.plugin.manifest.isFailure)
        assertTrue(f.trust.trusted().isEmpty())
        assertTrue(f.store.plugins().isEmpty())
    }

    @Test fun importNeedsTheFingerprintTheUserConfirmedAndLeavesNothingBehindOtherwise() {
        val f = Fixture()
        assertEquals(cert, f.center.fingerprint(pkg).getOrThrow())
        assertTrue(f.import(fingerprint = "b".repeat(64)).isFailure)
        assertTrue(f.import(fingerprint = "").isFailure)
        assertTrue(f.trust.trusted().isEmpty())
        assertTrue(f.store.plugins().isEmpty())
    }

    @Test fun importedPluginsStartDisabledWithoutBubbleAccess() {
        val f = Fixture()
        val record = f.import().getOrThrow()
        assertFalse(record.enabled)
        assertFalse(record.developerCreated)
        assertTrue(f.center.snapshot().candidates.single().imported)
        assertFalse(f.manager.hasBubbleAccess(pkg))
        assertTrue(f.store.bubbleServices().isEmpty())
        assertTrue("a disabled plugin has no usable commands", f.commands.list().none { it.owner == pkg })
    }

    @Test fun importRefusesAPluginThatChangedWhileItWasBeingReviewed() {
        val f = Fixture()
        assertTrue(f.import(versionCode = 5).isFailure)
        assertTrue(f.trust.trusted().isEmpty())
    }

    @Test fun importFailsWhenThePluginAppIsGone() {
        val f = Fixture()
        f.installed = null
        assertTrue(f.import().isFailure)
        assertTrue(f.trust.trusted().isEmpty())
    }

    @Test fun importRollsBackThePinWhenTheIdIsReserved() {
        val f = Fixture(reserved = setOf(pkg))
        assertTrue(f.import().isFailure)
        assertTrue("no half-imported plugin", f.trust.trusted().isEmpty())
        assertTrue(f.store.plugins().isEmpty())
    }

    @Test fun enablingAfterDisablingRegistersTheAdapterAgain() {
        val f = Fixture()
        f.import()
        assertTrue(f.center.setEnabled(pkg, true).isSuccess)
        assertTrue(f.store.plugins().single().enabled)
        assertEquals(setOf(pkg), f.store.pluginRuntime().registeredPluginIds())
        assertEquals(1, f.commands.listByOwner(pkg).size)

        assertTrue(f.center.setEnabled(pkg, false).isSuccess)
        assertFalse(f.store.plugins().single().enabled)
        assertTrue(f.store.pluginRuntime().registeredPluginIds().isEmpty())
        assertTrue(f.bubbleServices.isEmpty())

        assertTrue(f.center.setEnabled(pkg, true).isSuccess)
        assertEquals(setOf(pkg), f.store.pluginRuntime().registeredPluginIds())
        assertEquals(setOf(pkg), f.bubbleServices)
    }

    @Test fun enablingNeverGrantsBubbleAccessAndDisablingWithdrawsIt() {
        val f = Fixture()
        f.import()
        assertTrue("not enabled yet", f.manager.setBubbleAccess(pkg, true).isFailure)
        f.center.setEnabled(pkg, true)
        assertFalse(f.manager.hasBubbleAccess(pkg))
        assertTrue(f.manager.setBubbleAccess(pkg, true).isSuccess)
        assertEquals(setOf("notes"), f.store.bubbleServices())
        f.center.setEnabled(pkg, false)
        assertTrue(f.store.bubbleServices().isEmpty())
        f.center.setEnabled(pkg, true)
        assertFalse("turning it back on does not bring Bubble access back", f.manager.hasBubbleAccess(pkg))
    }

    @Test fun aChangedSigningCertificateKeepsThePluginBlockedAndRedrawingDoesNotFloodTheLog() {
        val f = Fixture()
        f.import()
        f.center.setEnabled(pkg, true)
        f.signatures.signers[pkg] = listOf("b".repeat(64))
        repeat(3) { assertEquals(ExternalStatus.SignatureChanged, f.center.snapshot().statuses[pkg]) }
        assertEquals("looking at the screen must not write to Activity", 0, f.blockedEvents())
        f.center.setEnabled(pkg, false)
        val again = f.center.setEnabled(pkg, true)
        assertTrue(again.isFailure)
        assertEquals("an actual attempt to enable is recorded", 1, f.blockedEvents())
        assertFalse(f.store.plugins().single().enabled)
    }

    @Test fun anUpdateWithMorePowersTurnsThePluginOffUntilTheUserApprovesIt() {
        val f = Fixture()
        f.import()
        f.center.setEnabled(pkg, true)
        f.installed = manifest(versionCode = 2, extraCommand = true)

        val snapshot = f.center.snapshot()
        val status = snapshot.statuses[pkg]
        assertTrue(status is ExternalStatus.UpdatePending)
        assertNotNull(snapshot.pendingUpdates[pkg])
        assertFalse("must be off until approved", f.store.plugins().single().enabled)
        assertTrue(f.center.setEnabled(pkg, true).isFailure)
        assertTrue("looking again does not repeat the decision", f.center.snapshot().statuses[pkg] is ExternalStatus.UpdatePending)

        assertTrue(f.center.approveUpdate(pkg).isSuccess)
        assertEquals(ExternalStatus.Ready, f.center.snapshot().statuses[pkg])
        assertFalse("approving does not turn it on", f.store.plugins().single().enabled)
        assertTrue(f.center.setEnabled(pkg, true).isSuccess)
        assertEquals(setOf("add_note", "find_note"), f.commands.listByOwner(pkg).map { it.name }.toSet())
    }

    @Test fun anUpdateWithTheSamePowersIsAcceptedWithoutAskingAndKeepsThePluginOn() {
        val f = Fixture()
        f.import()
        f.center.setEnabled(pkg, true)
        f.installed = manifest(versionCode = 2)
        assertEquals(ExternalStatus.Ready, f.center.snapshot().statuses[pkg])
        assertEquals(2L, f.trust.trusted().single().trustedManifest.versionCode)
        assertEquals("1.0.2", f.store.plugins().single().version)
        assertTrue(f.store.plugins().single().enabled)
    }

    @Test fun aDowngradeIsRefusedAndTurnsThePluginOff() {
        val f = Fixture()
        f.installed = manifest(versionCode = 3)
        f.center.import(pkg, 3, cert)
        f.center.setEnabled(pkg, true)
        f.installed = manifest(versionCode = 2)
        f.center.snapshot()
        assertFalse(f.store.plugins().single().enabled)
        assertEquals(3L, f.trust.trusted().single().trustedManifest.versionCode)
    }

    @Test fun bubbleBindingsComeFromWhatTheUserTrustedNotFromWhatIsRegisteredRightNow() {
        val f = Fixture()
        assertTrue(f.center.bubbleBindings().isEmpty())
        f.import()
        assertEquals(mapOf(pkg to "notes"), f.center.bubbleBindings())
        f.center.setEnabled(pkg, true)
        f.center.setEnabled(pkg, false)
        assertEquals(mapOf(pkg to "notes"), f.center.bubbleBindings())
    }

    @Test fun aBubbleServiceNameAlreadyTakenIsNotGivenToAPlugin() {
        val f = Fixture(reservedServices = setOf("notes"))
        assertTrue(f.center.bubbleServiceConflict(manifest()))
        f.import()
        f.center.setEnabled(pkg, true)
        assertTrue(f.center.bubbleBindings().isEmpty())
        assertNull(f.manager.bubbleServiceOf(pkg))
        assertTrue(f.manager.setBubbleAccess(pkg, true).isFailure)
        assertTrue(f.store.bubbleServices().isEmpty())
    }

    @Test fun twoPluginsClaimingTheSameServiceNameBothLoseIt() {
        val f = Fixture()
        f.import()
        assertEquals(mapOf(pkg to "notes"), f.center.bubbleBindings())
        val other = manifest().copy(packageName = "dev.example.other", name = "Other")
        f.signatures.signers[other.packageName] = listOf(cert)
        assertTrue(f.center.bubbleServiceConflict(other))
        f.trust.import(other, cert).getOrThrow()
        assertTrue("neither plugin may use an ambiguous name", f.center.bubbleBindings().isEmpty())
        assertTrue(f.center.bubbleServiceConflict(manifest()))
    }

    @Test fun removingAnExternalPluginForgetsItsPinCommandsAndBubbleService() {
        val f = Fixture()
        f.import()
        f.center.setEnabled(pkg, true)
        f.manager.setBubbleAccess(pkg, true)
        assertTrue(f.manager.remove(pkg).isSuccess)
        assertTrue(f.trust.trusted().isEmpty())
        assertTrue(f.store.plugins().isEmpty())
        assertTrue(f.store.bubbleServices().isEmpty())
        assertTrue(f.bubbleServices.isEmpty())
        assertEquals("its commands are gone", 0, f.commands.unregisterOwner(pkg))
        assertFalse("the app is still installed, so it can be reviewed and imported again", f.center.snapshot().candidates.single().imported)
    }

    @Test fun pluginsTheCenterDoesNotManageAreLeftAlone() {
        val f = Fixture()
        assertFalse(f.center.onRemoved("one.some.builtin"))
        assertFalse(f.center.isExternal("one.some.builtin"))
        assertNull(f.center.snapshot().statuses["one.some.builtin"])
    }
}
