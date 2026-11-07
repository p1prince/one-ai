package com.oneai.plugin.sdk

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PluginCallRunnerTest {
    private class Sink : ReplySink {
        val events = CopyOnWriteArrayList<String>()
        val latch = CountDownLatch(1)
        override fun result(requestId: String, title: String, content: String) { events += "result:$requestId:$title:$content"; latch.countDown() }
        override fun error(requestId: String, code: String, message: String) { events += "error:$requestId:$code"; latch.countDown() }
        override fun activity(requestId: String, message: String) { events += "activity:$requestId:$message" }
        override fun launch(requestId: String, label: String, payload: Any) { events += "launch:$requestId:$label:$payload" }
        fun await() = assertTrue("no reply", latch.await(5, TimeUnit.SECONDS))
    }

    private var runner: PluginCallRunner? = null
    @After fun stop() { runner?.shutdown() }

    private fun runner(timeoutMs: Long = 5_000, handler: (String, Map<String, String>, CallContext) -> PluginOutput): PluginCallRunner =
        PluginCallRunner(timeoutMs = timeoutMs, handler = handler).also { runner = it }

    @Test fun returnsResultsAndActivity() {
        val sink = Sink()
        runner { cmd, args, ctx -> ctx.reportActivity("working"); PluginOutput("T", "$cmd:${args["x"]}") }
            .execute("r1", "echo", listOf("x"), listOf("hi"), sink)
        sink.await()
        assertEquals(listOf("activity:r1:working", "result:r1:T:echo:hi"), sink.events.toList())
    }

    @Test fun rejectsInvalidInputWithoutRunningTheHandler() {
        var called = false
        val r = runner { _, _, _ -> called = true; PluginOutput("", "") }
        val a = Sink(); r.execute("r1", "Bad Name", emptyList(), emptyList(), a); a.await()
        val b = Sink(); r.execute("r2", "echo", listOf("k"), emptyList(), b); b.await()
        assertEquals(listOf("error:r1:INVALID_COMMAND"), a.events.toList())
        assertEquals(listOf("error:r2:INVALID_ARGUMENTS"), b.events.toList())
        assertTrue(!called)
    }

    @Test fun onlyOneRequestInFlight() {
        val gate = CountDownLatch(1)
        val r = runner { _, _, _ -> gate.await(5, TimeUnit.SECONDS); PluginOutput("T", "ok") }
        val first = Sink(); r.execute("r1", "echo", emptyList(), emptyList(), first)
        val second = Sink(); r.execute("r2", "echo", emptyList(), emptyList(), second)
        second.await()
        assertEquals(listOf("error:r2:BUSY"), second.events.toList())
        gate.countDown(); first.await()
        assertEquals(listOf("result:r1:T:ok"), first.events.toList())
    }

    @Test fun timeoutRepliesOnceAndFreesTheSlot() {
        val r = runner(timeoutMs = 100) { _, _, _ -> Thread.sleep(5_000); PluginOutput("T", "late") }
        val sink = Sink(); r.execute("r1", "echo", emptyList(), emptyList(), sink); sink.await()
        Thread.sleep(150)
        assertEquals(listOf("error:r1:TIMEOUT"), sink.events.toList())
        val next = Sink(); r.execute("r2", "echo", emptyList(), emptyList(), next); next.await()
        assertTrue(next.events.single().startsWith("error:r2:TIMEOUT"))
    }

    @Test fun cancelStopsTheRequest() {
        val r = runner { _, _, _ -> Thread.sleep(5_000); PluginOutput("T", "late") }
        val sink = Sink(); r.execute("r1", "echo", emptyList(), emptyList(), sink)
        r.cancel("other"); r.cancel("r1"); sink.await()
        Thread.sleep(100)
        assertEquals(listOf("error:r1:CANCELLED"), sink.events.toList())
    }

    @Test fun pluginExceptionsKeepASafeCodeAndUnexpectedOnesLeakNothing() {
        val r = runner { cmd, _, _ ->
            if (cmd == "clean") throw PluginException("NO_NETWORK", "Offline")
            if (cmd == "weird") throw PluginException("lower case /secret", "x")
            throw IllegalStateException("password=hunter2 at /data/user/0/app")
        }
        val a = Sink(); r.execute("a1", "clean", emptyList(), emptyList(), a); a.await()
        Thread.sleep(50); val b = Sink(); r.execute("b1", "weird", emptyList(), emptyList(), b); b.await()
        Thread.sleep(50); val c = Sink(); r.execute("c1", "crash", emptyList(), emptyList(), c); c.await()
        assertEquals("error:a1:NO_NETWORK", a.events.single())
        assertEquals("error:b1:PLUGIN_ERROR", b.events.single())
        assertEquals("error:c1:PLUGIN_ERROR", c.events.single())
    }

    @Test fun oversizedOutputFailsInsteadOfTruncating() {
        val sink = Sink()
        runner { _, _, _ -> PluginOutput("T", "x".repeat(20_001)) }.execute("r1", "echo", emptyList(), emptyList(), sink)
        sink.await()
        assertEquals(listOf("error:r1:OUTPUT_TOO_LARGE"), sink.events.toList())
    }

    @Test fun invalidRequestIdGetsNoReply() {
        val sink = Sink()
        runner { _, _, _ -> PluginOutput("T", "c") }.execute("bad id!", "echo", emptyList(), emptyList(), sink)
        Thread.sleep(100)
        assertTrue(sink.events.isEmpty())
    }

    @Test fun aRequestedScreenIsDeliveredOnceAndBeforeTheResult() {
        val sink = Sink()
        runner { _, _, _ -> PluginOutput("Opening", "ok", PluginLaunch("Open Calculator", "intent-token")) }
            .execute("r1", "echo", emptyList(), emptyList(), sink)
        sink.await()
        assertEquals(listOf("launch:r1:Open Calculator:intent-token", "result:r1:Opening:ok"), sink.events.toList())
    }

    @Test fun anInvalidScreenLabelFailsTheCallAndDeliversNoScreen() {
        listOf("", "   ", "x".repeat(101)).forEachIndexed { i, label ->
            val sink = Sink()
            runner { _, _, _ -> PluginOutput("T", "c", PluginLaunch(label, "token")) }
                .execute("r$i", "echo", emptyList(), emptyList(), sink)
            sink.await()
            assertEquals(listOf("error:r$i:OUTPUT_TOO_LARGE"), sink.events.toList())
            Thread.sleep(50)
        }
    }

    @Test fun commandsWithoutAScreenBehaveAsBefore() {
        val sink = Sink()
        runner { _, _, _ -> PluginOutput("T", "c") }.execute("r1", "echo", emptyList(), emptyList(), sink)
        sink.await()
        assertEquals(listOf("result:r1:T:c"), sink.events.toList())
    }
}
