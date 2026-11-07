package com.oneai.app.core

import com.oneai.app.ai.AiProviderException
import com.oneai.app.commands.CommandException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreInfrastructureTest {
    private class FakeService(
        override val id: String,
        private val failStart: Boolean = false,
        private val failStop: Boolean = false,
        val events: MutableList<String> = mutableListOf()
    ) : CoreService {
        override fun start() { if (failStart) error("boom-secret"); events += "start:$id" }
        override fun stop() { if (failStop) error("boom-secret"); events += "stop:$id" }
    }

    @Test fun providerAndCommandErrorsKeepTheirCodes() {
        val p = CoreErrors.from(AiProviderException("RESPONSE_TOO_LARGE", "Too large"))
        assertEquals(CoreErrorKind.PROVIDER, p.kind); assertEquals("RESPONSE_TOO_LARGE", p.code); assertTrue(p.recoverable)
        val c = CoreErrors.from(CommandException("COMMAND_UNAVAILABLE", "Missing"))
        assertEquals(CoreErrorKind.COMMAND, c.kind); assertEquals("COMMAND_UNAVAILABLE", c.code)
    }

    @Test fun unexpectedErrorsNeverLeakRawMessages() {
        val e = CoreErrors.from(IllegalStateException("api-key=SECRET"))
        assertEquals(CoreErrorKind.UNEXPECTED, e.kind)
        assertFalse(e.message.contains("SECRET")); assertFalse(e.recoverable)
    }

    @Test fun servicesStartInOrderAndStopInReverse() {
        val events = mutableListOf<String>()
        val m = CoreServiceManager()
        m.register(FakeService("a", events = events)); m.register(FakeService("b", events = events))
        assertEquals(mapOf("a" to true, "b" to true), m.startAll())
        m.stopAll()
        assertEquals(listOf("start:a", "start:b", "stop:b", "stop:a"), events)
        assertEquals(ServiceState.STOPPED, m.state("a"))
    }

    @Test fun oneFailingServiceDoesNotBlockOthersAndIsReportedSafely() {
        val reported = mutableListOf<CoreError>()
        val m = CoreServiceManager { reported += it }
        m.register(FakeService("bad", failStart = true)); m.register(FakeService("good"))
        assertEquals(mapOf("bad" to false, "good" to true), m.startAll())
        assertEquals(ServiceState.FAILED, m.state("bad")); assertEquals(ServiceState.RUNNING, m.state("good"))
        assertEquals(1, reported.size); assertFalse(reported.single().message.contains("secret"))
    }

    @Test fun stopFailureIsIsolatedAndReported() {
        val reported = mutableListOf<CoreError>()
        val m = CoreServiceManager { reported += it }
        m.register(FakeService("bad", failStop = true)); m.register(FakeService("good"))
        m.startAll()
        assertEquals(mapOf("good" to true, "bad" to false), m.stopAll())
        assertEquals(ServiceState.STOPPED, m.state("good")); assertEquals(1, reported.size)
    }

    @Test fun duplicateBlankAndUnknownServicesAreRejectedAndStartIsIdempotent() {
        val events = mutableListOf<String>()
        val m = CoreServiceManager()
        assertTrue(m.register(FakeService("a", events = events)).isSuccess)
        assertTrue(m.register(FakeService("a")).isFailure)
        assertTrue(m.register(FakeService(" ")).isFailure)
        assertTrue(m.start("missing").isFailure)
        m.start("a"); m.start("a")
        assertEquals(listOf("start:a"), events)
    }

    @Test fun emptyServiceManagerIsValid() {
        val m = CoreServiceManager()
        assertTrue(m.startAll().isEmpty()); assertTrue(m.stopAll().isEmpty()); assertTrue(m.states().isEmpty())
    }

    @Test fun failingBackgroundTaskIsReportedAndSiblingsStillRun() = runBlocking {
        val reported = mutableListOf<Pair<String, CoreError>>()
        val tasks = BackgroundTasks(Dispatchers.Unconfined) { n, e -> reported += n to e }
        var ran = false
        tasks.launch("bad") { error("secret") }!!.join()
        tasks.launch("good") { ran = true }!!.join()
        assertTrue(ran); assertTrue(tasks.isActive)
        assertEquals("bad", reported.single().first)
        assertFalse(reported.single().second.message.contains("secret"))
    }

    @Test fun shutdownCancelsRunningTasksWithoutReportingAndRejectsNewOnes() = runBlocking {
        val reported = mutableListOf<CoreError>()
        val tasks = BackgroundTasks(Dispatchers.Unconfined) { _, e -> reported += e }
        val running = tasks.launch("long") { delay(60_000) }
        assertNotNull(running)
        tasks.shutdown()
        running!!.join()
        assertTrue(running.isCancelled); assertTrue(reported.isEmpty())
        assertFalse(tasks.isActive); assertNull(tasks.launch("late") {})
    }
}
