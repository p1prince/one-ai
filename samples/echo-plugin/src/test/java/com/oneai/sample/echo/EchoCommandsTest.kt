package com.oneai.sample.echo

import com.oneai.plugin.sdk.CallContext
import com.oneai.plugin.sdk.PluginBounds
import com.oneai.plugin.sdk.PluginException
import com.oneai.plugin.testkit.FakePluginHost
import com.oneai.plugin.testkit.PluginTestEvent
import org.junit.Assert.assertEquals
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
        val events = FakePluginHost(timeoutMs = 5_000, handler = commands::run).use { host ->
            host.execute("req-1", "echo", mapOf("text" to "hi")).await()
        }
        assertEquals(listOf(PluginTestEvent.Completed("req-1", "Echo", "hi")), events)
    }

    @Test fun invalidInputRunsThroughTheReusableFakeHostAsACleanError() {
        val events = FakePluginHost(timeoutMs = 5_000, handler = commands::run).use { host ->
            host.execute("req-2", "echo").await()
        }
        assertTrue(events.single() is PluginTestEvent.Failed)
        assertEquals("INVALID_ARGUMENT", (events.single() as PluginTestEvent.Failed).code)
    }
}
