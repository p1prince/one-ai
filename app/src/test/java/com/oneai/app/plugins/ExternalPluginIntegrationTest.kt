package com.oneai.app.plugins

import com.oneai.app.commands.CommandExecutor
import com.oneai.app.commands.CommandRegistry
import com.oneai.app.commands.CommandStatus
import com.oneai.app.core.PluginRecord
import com.oneai.app.core.PluginStore
import com.oneai.app.security.ActionAuditLogger
import com.oneai.app.security.PluginActionGateway
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Host-side behaviour of external plugins with a fake binder (real binding/signatures need a device). */
class ExternalPluginIntegrationTest {
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

    private class FakeChannel(val behaviour: suspend (String, String, Map<String, String>, (String) -> Unit) -> PluginActionOutput) : PluginChannel {
        var closed = false
        val cancelled = mutableListOf<String>()
        override suspend fun describe() = """{"sdkApi":2}"""
        override suspend fun execute(requestId: String, command: String, args: Map<String, String>, onActivity: (String) -> Unit) =
            behaviour(requestId, command, args, onActivity)
        override suspend fun view(serviceId: String): String? = """{"views":[]}"""
        override fun cancel(requestId: String) { cancelled += requestId }
        override fun close() { closed = true }
    }

    private class Fixture(val pkg: String, val cert: String) {
        val store = FakeStore()
        val signatures = FakeSignatures().also { it.signers[pkg] = listOf(cert) }
        val trust = PluginTrustRegistry(InMemoryTrustStore(), signatures) { store.log(it) }
        val gateway = PluginActionGateway({ id -> store.plugins().firstOrNull { it.id == id } }, ActionAuditLogger { store.log(it) })
        val runtime = PluginRuntime({ id -> store.plugins().firstOrNull { it.id == id } }, gateway)
        val commands = CommandRegistry { id -> store.plugins().any { it.id == id && it.enabled } }
        val channels = mutableListOf<FakeChannel>()
        var behaviour: suspend (String, String, Map<String, String>, (String) -> Unit) -> PluginActionOutput =
            { _, command, args, _ -> PluginActionOutput("Done", "$command:${args["text"]}") }
        var opens = 0
        val factory = PluginChannelFactory { _, verify ->
            opens++
            check(verify()) { "refused: not trusted" }
            FakeChannel { r, c, a, act -> behaviour(r, c, a, act) }.also { channels += it }
        }
        fun registrar(reserved: Set<String> = emptySet(), timeoutMs: Long = 2_000) =
            ExternalPluginRegistrar(store, runtime, runtime, trust, commands, factory, reserved, timeoutMs)
        val executor = CommandExecutor(commands, { id, input, ticket -> runtime.invoke(id, input, ticket) }, { store.log(it) })
    }

    private fun manifest(versionCode: Long = 1, capabilities: List<String> = listOf("files"), extraCommand: Boolean = false): ExternalPluginManifest {
        val commands = mutableListOf(ExternalCommandDeclaration("add_note", "Add note", "Saves a note", "files"))
        if (extraCommand) commands += ExternalCommandDeclaration("find_note", "Find note", "", "files")
        return ExternalPluginManifest(
            packageName = pkg, versionName = "1.0.$versionCode", versionCode = versionCode, name = "Notes",
            description = "Quick notes", minCoreApi = 1, targetCoreApi = 1, capabilities = capabilities, commands = commands,
            bubble = ExternalBubbleDeclaration("notes", "Notes")
        )
    }

    /** Sensitive capabilities need a one-use ticket for the exact action, as the Bubble/Home approval dialog issues. */
    private suspend fun runApproved(f: Fixture, id: String, args: Map<String, String> = emptyMap()): com.oneai.app.commands.CommandResult {
        val action = f.executor.actionFor(id, args)!!
        val ticket = f.runtime.approveSensitiveAction(id.substringBefore('/'), action).getOrThrow()
        return f.executor.execute(id, args, ticket)
    }

    private fun imported(f: Fixture, m: ExternalPluginManifest = manifest()) = f.trust.import(m, cert).getOrThrow()

    @Test fun handshakeParsing() {
        assertEquals(1, PluginHandshake.parse("""{"sdkApi":1}"""))
        assertEquals(2, PluginHandshake.parse("""{"sdkApi":2}"""))
        assertNull(PluginHandshake.parse("nonsense"))
        assertNull(PluginHandshake.parse("x".repeat(500)))
        // Host API 2 accepts the backward-compatible wire contract 1 and the current contract 2.
        assertTrue(PluginHandshake.isSupported("""{"sdkApi":1}"""))
        assertTrue(PluginHandshake.isSupported("""{"sdkApi":2}"""))
        assertFalse(PluginHandshake.isSupported("""{"sdkApi":2}""", hostLevel = 1))
        assertFalse(PluginHandshake.isSupported("""{"sdkApi":0}"""))
    }

    @Test fun registrationCreatesADisabledRecordWithoutBubbleAccessAndHidesCommands() {
        val f = Fixture(pkg, cert)
        val record = f.registrar().register(imported(f)).getOrThrow()
        assertFalse(record.enabled)
        assertFalse(record.developerCreated)
        assertTrue(f.store.bubbleServices().isEmpty())
        assertEquals(setOf(pkg), f.runtime.registeredPluginIds())
        assertTrue("disabled plugin's commands must be hidden", f.commands.list().isEmpty())
    }

    @Test fun enabledPluginRunsItsCommandThroughTheGatewayAndTheChannel() = runBlocking {
        val f = Fixture(pkg, cert)
        f.registrar().register(imported(f))
        f.store.savePlugin(f.store.plugins().single().copy(enabled = true))
        assertEquals(listOf("$pkg/add_note"), f.commands.list().map { it.id })
        f.behaviour = { _, command, args, activity -> activity("saving"); PluginActionOutput("Saved", "$command:${args["text"]}") }
        val unapproved = f.executor.execute("$pkg/add_note", mapOf("text" to "hello"))
        assertEquals(CommandStatus.AWAITING_APPROVAL, unapproved.status)
        assertEquals("no connection may open before approval", 0, f.opens)
        val result = runApproved(f, "$pkg/add_note", mapOf("text" to "hello"))
        assertEquals(CommandStatus.COMPLETED, result.status)
        assertEquals("add_note:hello", result.content)
        assertTrue(f.channels.single().closed)
        assertTrue(f.store.logs.any { it.contains("Plugin: saving") })
    }

    @Test fun disabledOrRemovedPluginsCannotRun() = runBlocking {
        val f = Fixture(pkg, cert)
        f.registrar().register(imported(f))
        assertEquals(CommandStatus.NOT_FOUND, f.executor.execute("$pkg/add_note").status)
        f.store.savePlugin(f.store.plugins().single().copy(enabled = true))
        f.registrar().deactivate(pkg, "test")
        assertFalse(f.store.plugins().single().enabled)
        assertEquals(CommandStatus.NOT_FOUND, f.executor.execute("$pkg/add_note").status)
        assertEquals(0, f.opens)
    }

    @Test fun pluginFailuresAreContainedAndReported() = runBlocking {
        val f = Fixture(pkg, cert)
        f.registrar().register(imported(f))
        f.store.savePlugin(f.store.plugins().single().copy(enabled = true))
        f.behaviour = { _, _, _, _ -> throw IllegalStateException("password=hunter2 /data/secret") }
        val crashed = runApproved(f, "$pkg/add_note")
        assertEquals(CommandStatus.FAILED, crashed.status)
        assertFalse((crashed.error?.message ?: "").contains("hunter2"))
        f.behaviour = { _, _, _, _ -> throw PluginChannelException("NO_NETWORK", "offline") }
        assertEquals(CommandStatus.FAILED, runApproved(f, "$pkg/add_note").status)
        f.behaviour = { _, command, _, _ -> PluginActionOutput("Ok", command) }
        assertEquals(CommandStatus.COMPLETED, runApproved(f, "$pkg/add_note").status)
        assertTrue(f.channels.all { it.closed })
    }

    @Test fun slowPluginTimesOutAndIsCancelled() = runBlocking {
        val f = Fixture(pkg, cert)
        f.registrar(timeoutMs = 100).register(imported(f))
        f.store.savePlugin(f.store.plugins().single().copy(enabled = true))
        f.behaviour = { _, _, _, _ -> delay(5_000); PluginActionOutput("Late", "late") }
        val result = runApproved(f, "$pkg/add_note")
        assertEquals(CommandStatus.FAILED, result.status)
        assertEquals(1, f.channels.single().cancelled.size)
        assertTrue(f.channels.single().closed)
    }

    @Test fun adapterRefusesUndeclaredCommandsAndCapabilityMismatch() = runBlocking {
        val f = Fixture(pkg, cert)
        val entry = imported(f)
        val adapter = ExternalPluginAdapter(entry.trustedManifest, f.trust, f.factory)
        try { adapter.execute(PluginActionInput("delete_everything", "files", "x")); fail() } catch (e: IllegalArgumentException) { }
        try { adapter.execute(PluginActionInput("add_note", "internet", "x")); fail() } catch (e: IllegalArgumentException) { }
        assertEquals(0, f.opens)
    }

    @Test fun replacedSigningKeyStopsExecutionBeforeAnyBind() = runBlocking {
        val f = Fixture(pkg, cert)
        val entry = imported(f)
        val adapter = ExternalPluginAdapter(entry.trustedManifest, f.trust, f.factory)
        f.signatures.signers[pkg] = listOf("b".repeat(64))
        try { adapter.execute(PluginActionInput("add_note", "files", "x")); fail() } catch (e: SecurityException) { }
        assertEquals(0, f.opens)
    }

    @Test fun anUpdateThatAddsPowerDeactivatesUntilApprovedThenStaysDisabled() {
        val f = Fixture(pkg, cert)
        val registrar = f.registrar()
        registrar.register(imported(f))
        f.store.savePlugin(f.store.plugins().single().copy(enabled = true))
        assertEquals(1, f.commands.list().size)

        val decision = registrar.applyUpdate(manifest(versionCode = 2, extraCommand = true))
        assertTrue(decision is UpdateDecision.ConsentRequired)
        assertFalse(f.store.plugins().single().enabled)
        assertTrue(f.commands.list().isEmpty())
        assertTrue(f.runtime.registeredPluginIds().isEmpty())

        val record = registrar.approvePendingUpdate(pkg).getOrThrow()
        assertFalse("approval must not silently re-enable", record.enabled)
        assertEquals("1.0.2", record.version)
        f.store.savePlugin(record.copy(enabled = true))
        assertEquals(setOf("$pkg/add_note", "$pkg/find_note"), f.commands.list().map { it.id }.toSet())
    }

    @Test fun harmlessUpdateRefreshesInPlaceKeepingEnabledState() {
        val f = Fixture(pkg, cert)
        val registrar = f.registrar()
        registrar.register(imported(f))
        f.store.savePlugin(f.store.plugins().single().copy(enabled = true))
        assertEquals(UpdateDecision.Accepted, registrar.applyUpdate(manifest(versionCode = 2)))
        assertTrue(f.store.plugins().single().enabled)
        assertEquals("1.0.2", f.store.plugins().single().version)
    }

    @Test fun restoreAllBringsBackTrustedPluginsAndDisablesTheRest() {
        val f = Fixture(pkg, cert)
        val registrar = f.registrar()
        registrar.register(imported(f))
        f.store.savePlugin(f.store.plugins().single().copy(enabled = true))
        f.runtime.unregister(pkg) // simulate a process restart
        f.commands.unregisterOwner(pkg)
        registrar.restoreAll()
        assertEquals(setOf(pkg), f.runtime.registeredPluginIds())
        assertTrue(f.store.plugins().single().enabled)

        f.signatures.signers[pkg] = listOf("c".repeat(64))
        registrar.restoreAll()
        assertFalse(f.store.plugins().single().enabled)
        assertTrue(f.runtime.registeredPluginIds().isEmpty())
        assertTrue(f.commands.list().isEmpty())
    }

    @Test fun builtInIdsAreReservedAndNothingIsLeftBehind() {
        val f = Fixture(pkg, cert)
        val result = f.registrar(reserved = setOf(pkg)).register(imported(f))
        assertTrue(result.isFailure)
        assertTrue(f.store.plugins().isEmpty())
        assertTrue(f.runtime.registeredPluginIds().isEmpty())
    }

    @Test fun removalDropsCommandsAndThePin() {
        val f = Fixture(pkg, cert)
        val registrar = f.registrar()
        registrar.register(imported(f))
        registrar.onRemoved(pkg)
        assertTrue(f.trust.trusted().isEmpty())
        assertTrue(f.commands.list().isEmpty())
    }

    @Test fun nothingHappensWithZeroExternalPlugins() {
        val f = Fixture(pkg, cert)
        f.registrar().restoreAll()
        assertTrue(f.store.plugins().isEmpty())
        assertNotNull(f.commands)
        assertEquals(0, f.opens)
    }

    @Test fun bubbleHooksFireOnRegisterDeactivateAndRemoval() = runBlocking {
        val f = Fixture(pkg, cert)
        val added = mutableListOf<String>(); val removed = mutableListOf<String>()
        var fetched: String? = null
        val registrar = ExternalPluginRegistrar(
            f.store, f.runtime, f.runtime, f.trust, f.commands, f.factory, emptySet(), 2_000,
            onBubbleService = { m, fetch -> added += m.id; fetched = runBlocking { fetch("notes") } },
            onBubbleRemoved = { removed += it }
        )
        registrar.register(imported(f))
        assertEquals(listOf(pkg), added)
        assertNotNull(fetched)
        // Registering first clears any stale service, so a re-registration replaces rather than duplicates.
        assertEquals(listOf(pkg), removed)
        registrar.deactivate(pkg, "test")
        assertEquals(listOf(pkg, pkg), removed)
        registrar.onRemoved(pkg)
        assertEquals(listOf(pkg, pkg, pkg), removed)
    }
}
