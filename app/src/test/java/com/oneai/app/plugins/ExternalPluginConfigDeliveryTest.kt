package com.oneai.app.plugins

import com.oneai.app.commands.CommandExecutor
import com.oneai.app.commands.CommandRegistry
import com.oneai.app.commands.CommandStatus
import com.oneai.app.core.PluginRecord
import com.oneai.app.core.PluginStore
import com.oneai.app.security.ActionAuditLogger
import com.oneai.app.security.PluginActionGateway
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Stage 9.6c (decision D5): the host pushes the validated settings snapshot to a plugin right after the bind
 * and before any command or view, only to plugins that declare settings and report wire level 3, and never
 * truncates or logs values. A fake binder is used, so real Binder delivery is not covered here (Stage 11).
 */
class ExternalPluginConfigDeliveryTest {
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

    /** Records every call in order so tests can assert that the snapshot arrives before the command or view. */
    private class RecordingChannel(
        override val sdkApi: Int,
        private val calls: MutableList<String>,
        private val applied: MutableList<Pair<Int, Map<String, String>>>
    ) : PluginChannel {
        var closed = false
        override suspend fun describe() = """{"sdkApi":$sdkApi}"""
        override suspend fun applyConfig(schemaVersion: Int, values: Map<String, String>) {
            calls += "applyConfig"
            applied += schemaVersion to values
        }
        override suspend fun execute(requestId: String, command: String, args: Map<String, String>, onActivity: (String) -> Unit): PluginActionOutput {
            calls += "execute"
            return PluginActionOutput("Done", command)
        }
        override suspend fun view(serviceId: String): String? {
            calls += "view"
            return """{"views":[]}"""
        }
        override fun cancel(requestId: String) {}
        override fun close() { closed = true; calls += "close" }
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

    private fun manifest(withSettings: Boolean) = ExternalPluginManifest(
        packageName = pkg, versionName = "1.0.1", versionCode = 1, name = "Notes", description = "Quick notes",
        minCoreApi = if (withSettings) PluginApi.SETTINGS_MIN_API else 1,
        targetCoreApi = if (withSettings) PluginApi.SETTINGS_MIN_API else 1,
        capabilities = listOf("files"),
        commands = listOf(ExternalCommandDeclaration("add_note", "Add note", "Saves a note", "files")),
        bubble = ExternalBubbleDeclaration("notes", "Notes"),
        settings = if (withSettings) schema else null
    )

    private inner class Fixture(withSettings: Boolean = true, val pluginLevel: Int = 3) {
        val manifest = manifest(withSettings)
        val store = FakeStore()
        val configStore = FakePluginConfigStore()
        val signatures = FakeSignatures().also { it.signers[pkg] = listOf(cert) }
        val trustStore = InMemoryTrustStore().also { it.put(TrustEntry(pkg, cert, manifest)) }
        val trust = PluginTrustRegistry(trustStore, signatures) { store.log(it) }
        val gateway = PluginActionGateway({ id -> store.plugins().firstOrNull { it.id == id } }, ActionAuditLogger { store.log(it) })
        val runtime = PluginRuntime({ id -> store.plugins().firstOrNull { it.id == id } }, gateway)
        val commands = CommandRegistry { id -> store.plugins().any { it.id == id && it.enabled } }
        val executor = CommandExecutor(commands, { id, input, ticket -> runtime.invoke(id, input, ticket) }, { store.log(it) })
        val calls = mutableListOf<String>()
        val applied = mutableListOf<Pair<Int, Map<String, String>>>()
        val channels = mutableListOf<RecordingChannel>()
        val factory = PluginChannelFactory { _, verify ->
            check(verify()) { "refused: not trusted" }
            RecordingChannel(pluginLevel, calls, applied).also { channels += it }
        }
        val configuration = PluginConfiguration(
            configStore,
            schemaOf = { id -> (trust.peek(id) as? TrustCheck.Trusted)?.entry?.trustedManifest?.settings },
            log = { store.log(it) }
        )
        val registrar = ExternalPluginRegistrar(
            store, runtime, runtime, trust, commands, factory, emptySet(), 2_000, configuration = configuration
        )

        /** Registers the plugin, enables it as the user would, and activates its runtime. */
        fun enable() {
            registrar.register(trustStore.all().single())
            store.savePlugin(store.plugins().single().copy(enabled = true))
            assertTrue(registrar.activate(pkg).isSuccess)
        }

        suspend fun runCommand(): CommandStatus {
            val id = "$pkg/add_note"
            val action = executor.actionFor(id, emptyMap())!!
            val ticket = runtime.approveSensitiveAction(pkg, action).getOrThrow()
            return executor.execute(id, emptyMap(), ticket).status
        }
    }

    @Test fun settingsAreDeliveredAfterTheBindAndBeforeTheCommand() {
        runBlocking {
            val f = Fixture()
            f.configuration.update(pkg, mapOf("mode" to "fancy")).getOrThrow()
            f.enable()
            assertEquals(CommandStatus.COMPLETED, f.runCommand())
            assertEquals(listOf("applyConfig", "execute", "close"), f.calls)
            val (version, values) = f.applied.single()
            assertEquals(schema.version, version)
            assertEquals("fancy", values["mode"])
            assertEquals("the declared default is sent when nothing is stored", "1", values["repeat"])
        }
    }

    @Test fun aChangeReachesThePluginOnItsNextBind() {
        runBlocking {
            val f = Fixture()
            f.configuration.update(pkg, mapOf("mode" to "plain")).getOrThrow()
            f.enable()
            f.runCommand()
            f.configuration.update(pkg, mapOf("mode" to "fancy", "repeat" to "4")).getOrThrow()
            f.runCommand()
            assertEquals("plain", f.applied[0].second["mode"])
            assertEquals("fancy", f.applied[1].second["mode"])
            assertEquals("4", f.applied[1].second["repeat"])
        }
    }

    @Test fun theSnapshotAlsoPrecedesAViewRequest() {
        runBlocking {
            val f = Fixture()
            f.configuration.update(pkg, mapOf("mode" to "plain")).getOrThrow()
            val adapter = ExternalPluginAdapter(f.manifest, f.trust, f.factory, settings = { PluginConfigSnapshot(schema.version, f.configuration.effective(pkg)) })
            assertNotNull(adapter.view("notes"))
            assertEquals(listOf("applyConfig", "view", "close"), f.calls)
        }
    }

    @Test fun aPluginWithoutSettingsIsNeverSentAConfigurationCall() {
        runBlocking {
            val f = Fixture(withSettings = false, pluginLevel = 3)
            f.enable()
            assertEquals(CommandStatus.COMPLETED, f.runCommand())
            assertEquals(listOf("execute", "close"), f.calls)
            assertTrue(f.applied.isEmpty())
        }
    }

    @Test fun aLevelTwoPluginWithoutSettingsKeepsWorkingUnchanged() {
        runBlocking {
            val f = Fixture(withSettings = false, pluginLevel = 2)
            f.enable()
            assertEquals(CommandStatus.COMPLETED, f.runCommand())
            assertEquals(listOf("execute", "close"), f.calls)
        }
    }

    @Test fun aPluginThatDeclaresSettingsButReportsAnOlderLevelIsRefusedBeforeAnyCommand() {
        runBlocking {
            val f = Fixture(withSettings = true, pluginLevel = 2)
            val adapter = ExternalPluginAdapter(f.manifest, f.trust, f.factory, settings = { PluginConfigSnapshot(1, mapOf("mode" to "plain")) })
            try {
                adapter.execute(PluginActionInput("add_note", "files", "x"))
                fail("expected the plugin to be refused")
            } catch (e: PluginChannelException) {
                assertEquals("INCOMPATIBLE", e.code)
            }
            assertEquals("nothing was sent to a plugin that cannot read it", listOf("close"), f.calls)
            assertTrue(f.channels.single().closed)
        }
    }

    @Test fun anOutOfBoundsSnapshotIsRefusedWholeAndNeverTruncated() {
        runBlocking {
            val f = Fixture()
            val tooMany = (1..PluginSettingsValidator.MAX_SETTINGS + 1).associate { "key$it" to "v" }
            val adapter = ExternalPluginAdapter(f.manifest, f.trust, f.factory, settings = { PluginConfigSnapshot(1, tooMany) })
            try {
                adapter.execute(PluginActionInput("add_note", "files", "x"))
                fail("expected the snapshot to be refused")
            } catch (e: PluginChannelException) {
                assertEquals("INVALID_CONFIG", e.code)
            }
            assertEquals(listOf("close"), f.calls)
            assertTrue(f.applied.isEmpty())
        }
    }

    @Test fun settingsValuesAreNeverWrittenToTheActivityLog() {
        runBlocking {
            val f = Fixture()
            f.configuration.update(pkg, mapOf("mode" to "fancy")).getOrThrow()
            f.enable()
            f.runCommand()
            assertFalse("a value must not appear in any log line", f.store.logs.any { it.contains("fancy") })
        }
    }

    @Test fun levelThreeIsNowProvidedAndSettingsPluginsAreImportable() {
        assertEquals(3, PluginApi.LEVEL)
        assertTrue(PluginHandshake.isSupported("""{"sdkApi":3}"""))
        assertFalse(PluginHandshake.isSupported("""{"sdkApi":4}"""))
        assertEquals(Compatibility.Compatible, PluginCompatibility.check(manifest(withSettings = true)))
        val f = Fixture()
        f.trustStore.remove(pkg)
        assertTrue("a plugin declaring settings can now be imported", f.trust.import(manifest(withSettings = true), cert).isSuccess)
    }
}
