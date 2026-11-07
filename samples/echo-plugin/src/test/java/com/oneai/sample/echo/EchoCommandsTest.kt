package com.oneai.sample.echo

import com.oneai.plugin.sdk.CallContext
import com.oneai.plugin.sdk.PluginBounds
import com.oneai.plugin.sdk.PluginConfig
import com.oneai.plugin.sdk.PluginException
import com.oneai.plugin.testkit.FakePluginHost
import com.oneai.plugin.testkit.PluginTestEvent
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class EchoCommandsTest {
    private val noActivity = CallContext { }
    private val commands = EchoCommands(sleep = { })

    private fun failure(block: () -> Unit): PluginException =
        try { block(); fail("expected a PluginException"); throw AssertionError() } catch (e: PluginException) { e }

    @Test fun echoReturnsTheTextUnchanged() {
        val output = commands.run("echo", mapOf("text" to "  Hello, ONE AI  "), noActivity)
        assertEquals("Echo", output.title)
        assertEquals("  Hello, ONE AI  ", output.content)
    }

    @Test fun reverseReversesTheText() {
        assertEquals("cba", commands.run("reverse", mapOf("text" to "abc"), noActivity).content)
    }

    @Test fun textIsRequiredAndBounded() {
        for (args in listOf(emptyMap<String, String>(), mapOf("text" to ""), mapOf("text" to "   "))) {
            assertEquals("INVALID_ARGUMENT", failure { commands.run("echo", args, noActivity) }.code)
        }
        assertEquals("INVALID_ARGUMENT", failure { commands.run("echo", mapOf("text" to "x".repeat(EchoCommands.MAX_TEXT + 1)), noActivity) }.code)
        // The largest accepted input must still fit the SDK's output bounds.
        val largest = commands.run("echo", mapOf("text" to "x".repeat(EchoCommands.MAX_TEXT)), noActivity)
        assertNull(PluginBounds.outputProblem(largest.title, largest.content))
    }

    @Test fun unknownCommandFailsCleanly() {
        assertEquals("UNKNOWN_COMMAND", failure { commands.run("nope", emptyMap(), noActivity) }.code)
    }

    @Test fun everyErrorCodeSurvivesTheSdkCodeFilter() {
        val codes = listOf(
            failure { commands.run("nope", emptyMap(), noActivity) }.code,
            failure { commands.run("echo", emptyMap(), noActivity) }.code,
            failure { commands.run("wait", mapOf("text" to "x", "seconds" to "0"), noActivity) }.code
        )
        codes.forEach { assertEquals(it, PluginBounds.safeErrorCode(it)) }
    }

    @Test fun waitValidatesSeconds() {
        for (seconds in listOf(null, "", "abc", "0", "-1", "61", "1.5")) {
            val args = mutableMapOf("text" to "x")
            if (seconds != null) args["seconds"] = seconds
            assertEquals("seconds=$seconds", "INVALID_ARGUMENT", failure { commands.run("wait", args, noActivity) }.code)
        }
    }

    @Test fun waitReportsProgressThenReturnsTheText() {
        val lines = mutableListOf<String>()
        val slept = mutableListOf<Long>()
        val output = EchoCommands(sleep = { slept += it }).run("wait", mapOf("text" to "done", "seconds" to "3"), CallContext { lines += it })
        assertEquals("done", output.content)
        assertEquals(listOf(1_000L, 1_000L, 1_000L), slept)
        assertEquals(listOf("Waited 1 of 3 s", "Waited 2 of 3 s", "Waited 3 of 3 s"), lines)
    }

    @Test fun waitStaysCancellableByLettingInterruptsEscape() {
        val interrupting = EchoCommands(sleep = { throw InterruptedException() })
        try {
            interrupting.run("wait", mapOf("text" to "x", "seconds" to "5"), noActivity)
            fail("expected an InterruptedException")
        } catch (expected: InterruptedException) {
            // the SDK relies on this to stop the worker on cancel or timeout
        }
    }

    @Test fun echoRunsThroughTheReusableFakeHostWithExactlyOneReply() {
        val events = FakePluginHost(timeoutMs = 5_000) { command, args, context -> commands.run(command, args, context) }.use { host ->
            host.execute("req-1", "echo", mapOf("text" to "hi")).await()
        }
        assertEquals(listOf(PluginTestEvent.Completed("req-1", "Echo", "hi")), events)
    }

    @Test fun invalidInputRunsThroughTheReusableFakeHostAsACleanError() {
        val events = FakePluginHost(timeoutMs = 5_000) { command, args, context -> commands.run(command, args, context) }.use { host ->
            host.execute("req-2", "echo").await()
        }
        assertTrue(events.single() is PluginTestEvent.Failed)
        assertEquals("INVALID_ARGUMENT", (events.single() as PluginTestEvent.Failed).code)
    }

    // ---- Settings (plugin API level 3) ----

    private fun withSettings(vararg values: Pair<String, String>): FakePluginHost {
        val host = FakePluginHost.withConfig(timeoutMs = 5_000) { command, args, context, config -> commands.run(command, args, context, config) }
        assertTrue(host.applyConfig(1, mapOf(*values)).isSuccess)
        return host
    }

    @Test fun withoutSettingsRepliesAreUnchanged() {
        assertEquals("Hello", commands.run("echo", mapOf("text" to "Hello"), noActivity, PluginConfig.EMPTY).content)
        // A host that never delivered a snapshot behaves the same as one that delivered only defaults.
        assertEquals("Hello", commands.run("echo", mapOf("text" to "Hello"), noActivity, PluginConfig(1, mapOf("uppercase" to "false"))).content)
    }

    @Test fun prefixAndUppercaseAreAppliedToEveryCommand() {
        withSettings("prefix" to ">> ", "uppercase" to "true").use { host ->
            val echo = host.execute("r1", "echo", mapOf("text" to "hi")).await().single() as PluginTestEvent.Completed
            val reverse = host.execute("r2", "reverse", mapOf("text" to "abc")).await().single() as PluginTestEvent.Completed
            assertEquals(">> HI", echo.content)
            assertEquals(">> CBA", reverse.content)
        }
    }

    @Test fun waitAlsoUsesTheSettings() {
        val output = commands.run("wait", mapOf("text" to "done", "seconds" to "1"), noActivity, PluginConfig(1, mapOf("prefix" to "[w] ")))
        assertEquals("[w] done", output.content)
    }

    @Test fun aChangeAppliesToTheNextCommand() {
        withSettings("prefix" to "A: ").use { host ->
            assertEquals("A: x", (host.execute("r1", "echo", mapOf("text" to "x")).await().single() as PluginTestEvent.Completed).content)
            assertTrue(host.applyConfig(1, mapOf("prefix" to "B: ")).isSuccess)
            assertEquals("B: x", (host.execute("r2", "echo", mapOf("text" to "x")).await().single() as PluginTestEvent.Completed).content)
        }
    }

    @Test fun anOutOfBoundsSnapshotIsRejectedAndThePreviousSettingsStay() {
        withSettings("prefix" to "ok: ").use { host ->
            val tooLong = mapOf("prefix" to "x".repeat(PluginBounds.MAX_CONFIG_VALUE + 1))
            assertTrue(host.applyConfig(1, tooLong).isFailure)
            assertEquals("ok: x", (host.execute("r1", "echo", mapOf("text" to "x")).await().single() as PluginTestEvent.Completed).content)
        }
    }

    @Test fun theLargestInputWithTheLargestPrefixStillFitsTheSdkOutputBounds() {
        val config = PluginConfig(1, mapOf("prefix" to "p".repeat(40), "uppercase" to "true"))
        val largest = commands.run("echo", mapOf("text" to "x".repeat(EchoCommands.MAX_TEXT)), noActivity, config)
        assertNull(PluginBounds.outputProblem(largest.title, largest.content))
    }

    @Test fun theKeysTheCodeReadsAreTheOnesTheManifestDeclares() {
        val manifest = File("src/main/res/xml/one_ai_plugin.xml").readText()
        assertTrue(manifest.contains("key=\"${EchoCommands.KEY_PREFIX}\""))
        assertTrue(manifest.contains("key=\"${EchoCommands.KEY_UPPERCASE}\""))
        assertTrue("declaring settings requires plugin API level 3", manifest.contains("minCoreApi=\"3\""))
        assertFalse("neither setting may be required, so the sample works as soon as it is enabled", manifest.contains("required=\"true\""))
    }
}
