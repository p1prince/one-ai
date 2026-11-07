package com.oneai.app.plugins

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stage 11 scaffolding: exercises the real Android pieces that JVM tests cannot (package discovery, XML
 * resource reading, signing-certificate reading, bound-service channel, the SDK's caller check) against the
 * installed echo plugin.
 *
 * **This has never been run.** CI only compiles it. It needs an emulator or device with BOTH debug builds
 * installed (ONE AI and samples/echo-plugin); see docs/EMULATOR_TEST_PLAN.md. If the echo plugin is missing
 * the tests FAIL on purpose, so a missing prerequisite can never look like a pass.
 *
 * Assumption to confirm on first run: instrumentation code runs in the ONE AI process, so the plugin's
 * caller check sees the ONE AI package. If it does not, calls fail with PLUGIN_UNAVAILABLE and the test
 * setup, not the plugin, needs attention.
 */
@RunWith(AndroidJUnit4::class)
class EchoPluginInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val pkg = "com.oneai.sample.echo"

    private val signatures = AndroidPackageSignatureSource(context.packageManager)
    private fun newRegistry() = PluginTrustRegistry(InMemoryTrustStore(), signatures)
    private val factory = BoundPluginChannelFactory(context)

    private fun discoveredManifest(): ExternalPluginManifest {
        val discovered = PluginDiscovery(context).discover().firstOrNull { it.packageName == pkg }
        assertNotNull("Echo plugin ($pkg) is not installed or not discoverable; install it first", discovered)
        return discovered!!.manifest.getOrThrow()
    }

    /** Imports the plugin the way the user would: the fingerprint shown is the one confirmed. */
    private fun importEcho(registry: PluginTrustRegistry): TrustEntry {
        val fingerprint = registry.fingerprintForImport(pkg).getOrThrow()
        return registry.import(discoveredManifest(), fingerprint).getOrThrow()
    }

    private fun <T> withChannel(registry: PluginTrustRegistry, block: suspend (PluginChannel) -> T): T = runBlocking {
        val channel = factory.open(pkg) { registry.check(pkg) is TrustCheck.Trusted }
        try { block(channel) } finally { channel.close() }
    }

    @Test fun discoveryReadsTheDeclaredManifestWithoutRunningPluginCode() {
        val manifest = discoveredManifest()
        assertEquals(pkg, manifest.id)
        assertEquals(listOf("echo", "reverse", "wait"), manifest.commands.map { it.name })
        assertEquals("echo", manifest.bubble?.service)
        assertTrue(PluginCompatibility.check(manifest).usable)
    }

    @Test fun importNeedsTheCorrectFingerprintAndThenPassesTheTrustCheck() {
        val registry = newRegistry()
        val wrong = registry.import(discoveredManifest(), "0".repeat(64))
        assertTrue("a wrong fingerprint must be refused", wrong.exceptionOrNull() is SecurityException)
        assertEquals(TrustCheck.NotImported, registry.check(pkg))

        importEcho(registry)
        assertTrue(registry.check(pkg) is TrustCheck.Trusted)
    }

    @Test fun bindingIsRefusedWhenTrustFailsAtBindTime() {
        val failure = runCatching { runBlocking { factory.open(pkg) { false } } }.exceptionOrNull()
        assertTrue(failure is PluginChannelException)
        assertEquals("NOT_TRUSTED", (failure as PluginChannelException).code)
    }

    @Test fun handshakeAndEchoWorkOverTheRealBinder() {
        val registry = newRegistry().also { importEcho(it) }
        withChannel(registry) { channel ->
            assertTrue(PluginHandshake.isSupported(channel.describe()))
            val output = channel.execute("req-echo", "echo", mapOf("text" to "hello")) { }
            assertEquals("Echo", output.title)
            assertEquals("hello", output.content)
        }
    }

    @Test fun theBubbleViewIsServedAndAcceptedByTheHostParser() {
        val registry = newRegistry()
        val manifest = importEcho(registry).trustedManifest
        withChannel(registry) { channel ->
            val parsed = ExternalViewParser.parse(channel.view("echo"), manifest)
            assertTrue(parsed.exceptionOrNull()?.message, parsed.isSuccess)
            assertNull("an undeclared service has no view", channel.view("not_declared"))
        }
    }

    @Test fun invalidInputComesBackAsACleanErrorCode() {
        val registry = newRegistry().also { importEcho(it) }
        withChannel(registry) { channel ->
            try {
                channel.execute("req-bad", "echo", emptyMap()) { }
                fail("expected a PluginChannelException")
            } catch (e: PluginChannelException) {
                assertEquals("INVALID_ARGUMENT", e.code)
            }
        }
    }

    @Test fun progressLinesArriveWhileACommandRuns() {
        val registry = newRegistry().also { importEcho(it) }
        val lines = mutableListOf<String>()
        withChannel(registry) { channel ->
            val output = channel.execute("req-wait", "wait", mapOf("text" to "done", "seconds" to "2")) { lines += it }
            assertEquals("done", output.content)
        }
        assertEquals(listOf("Waited 1 of 2 s", "Waited 2 of 2 s"), lines)
    }

    @Test fun aCommandThatOutlivesTheSdkDeadlineIsStoppedWithTimeout() {
        val registry = newRegistry().also { importEcho(it) }
        withChannel(registry) { channel ->
            try {
                // wait=60 s is above the SDK's 30 s default deadline; allow the real 30 s plus slack.
                withTimeout(45_000) { channel.execute("req-slow", "wait", mapOf("text" to "x", "seconds" to "60")) { } }
                fail("expected the deadline to stop the command")
            } catch (e: PluginChannelException) {
                assertEquals("TIMEOUT", e.code)
            }
        }
    }

    @Test fun theHostAdapterRunsACommandThroughTheRealChannel() {
        val registry = newRegistry()
        val trusted = importEcho(registry).trustedManifest
        val adapter = ExternalPluginAdapter(trusted, registry, factory)
        val output = runBlocking {
            adapter.execute(PluginActionInput("reverse", "commands", "Run command: Reverse text", mapOf("text" to "abc")))
        }
        assertEquals("cba", output.content)
    }
}
