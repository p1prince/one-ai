package com.oneai.plugin.testkit

import com.oneai.plugin.sdk.CallContext
import com.oneai.plugin.sdk.PluginException
import com.oneai.plugin.sdk.PluginOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class FakePluginHostTest {
    @Test fun capturesProgressAndTheFinalResultInOrder() {
        FakePluginHost(handler = { command, args, context ->
            context.reportActivity("working")
            PluginOutput("Echo", "$command:${args["text"]}")
        }).use { host ->
            val events = host.execute("request-1", "echo", mapOf("text" to "hello")).await()
            assertEquals(
                listOf(
                    PluginTestEvent.Progress("request-1", "working"),
                    PluginTestEvent.Completed("request-1", "Echo", "echo:hello")
                ),
                events
            )
        }
    }

    @Test fun convertsPluginExceptionsIntoSafeFailureEvents() {
        FakePluginHost(handler = { _, _, _ -> throw PluginException("OFFLINE", "No network") }).use { host ->
            val event = host.execute("request-2", "run").await().single()
            assertEquals(PluginTestEvent.Failed("request-2", "OFFLINE", "No network"), event)
        }
    }

    @Test fun rejectsMalformedArgumentsBeforeCallingTheHandler() {
        var invoked = false
        FakePluginHost(handler = { _, _, _ -> invoked = true; PluginOutput("x", "y") }).use { host ->
            val call = host.execute("request-3", "run", mapOf("bad key" to "value"))
            val event = call.await().single()
            assertTrue(event is PluginTestEvent.Failed)
            assertEquals("INVALID_ARGUMENTS", (event as PluginTestEvent.Failed).code)
            assertTrue(!invoked)
        }
    }

    @Test fun reportsRequestedLaunchBeforeTheFinalResult() {
        val token = Any()
        FakePluginHost(handler = { _, _, _ -> PluginOutput("Opening", "ready", com.oneai.plugin.sdk.PluginLaunch("Open screen", token)) }).use { host ->
            val events = host.execute("request-4", "open").await()
            assertEquals(PluginTestEvent.Launch("request-4", "Open screen", token), events.first())
            assertEquals(PluginTestEvent.Completed("request-4", "Opening", "ready"), events.last())
        }
    }

    @Test fun cancellationUsesTheSdkTerminalError() {
        FakePluginHost(timeoutMs = 10_000, handler = { _, _, _ ->
            Thread.sleep(10_000)
            PluginOutput("Late", "result")
        }).use { host ->
            val call = host.execute("request-5", "wait")
            host.cancel("request-5")
            assertEquals("CANCELLED", (call.await().single() as PluginTestEvent.Failed).code)
        }
    }

    @Test fun timeoutIsReportedByTheSdkRunner() {
        FakePluginHost(timeoutMs = 50, handler = { _, _, _ ->
            Thread.sleep(1_000)
            PluginOutput("Late", "result")
        }).use { host ->
            val event = host.execute("request-6", "wait").await(2_000).single()
            assertEquals("TIMEOUT", (event as PluginTestEvent.Failed).code)
        }
    }
}
