package com.oneai.plugin.sdk

import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * A screen the plugin asks ONE AI to open for the user after they agree (plugin API 2). [payload] must be an
 * `android.app.PendingIntent`; it is kept opaque here so this logic stays testable on the JVM. Build one with
 * [screenLaunch].
 */
class PluginLaunch(val label: String, val payload: Any)

data class PluginOutput(val title: String, val content: String, val launch: PluginLaunch? = null)

/** Throw from a command to report a clean, user-safe failure. */
class PluginException(val code: String, message: String) : Exception(message)

/** Handed to a command so it can report bounded progress. */
fun interface CallContext { fun reportActivity(message: String) }

/** Where results go. The Android layer adapts the AIDL callback to this; tests use a fake. */
interface ReplySink {
    fun result(requestId: String, title: String, content: String)
    fun error(requestId: String, code: String, message: String)
    fun activity(requestId: String, message: String)
    /** Called at most once, immediately before [result], when the command asked for a screen to be opened. */
    fun launch(requestId: String, label: String, payload: Any) {}
}

/**
 * Runs plugin commands under the SDK's guarantees: validated input, one request in flight,
 * a deadline, exactly one reply per request, bounded output, and no internal detail in errors.
 */
class PluginCallRunner(
    private val timeoutMs: Long = PluginBounds.DEFAULT_TIMEOUT_MS,
    private val workers: java.util.concurrent.ExecutorService = Executors.newCachedThreadPool(daemonThreads("one-ai-plugin-worker")),
    private val watchdog: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(daemonThreads("one-ai-plugin-watchdog")),
    private val handler: (command: String, args: Map<String, String>, context: CallContext) -> PluginOutput
) {
    private class Call(val id: String, val sink: ReplySink) {
        val done = AtomicBoolean(false)
        @Volatile var future: Future<*>? = null
        @Volatile var timer: ScheduledFuture<*>? = null
        var running = false
        var abandonedCounted = false
    }

    private val inFlight = AtomicReference<Call?>(null)
    private val abandoned = AtomicInteger(0)

    fun execute(requestId: String?, command: String?, keys: List<String>?, values: List<String>?, sink: ReplySink) {
        if (!PluginBounds.isValidRequestId(requestId)) return // nobody to answer safely
        val id = requestId!!
        if (!PluginBounds.isValidCommandName(command)) return sink.error(id, "INVALID_COMMAND", "Invalid command name")
        val args = PluginBounds.parseArgs(keys, values).getOrElse { return sink.error(id, "INVALID_ARGUMENTS", it.message ?: "Invalid arguments") }
        if (abandoned.get() >= MAX_ABANDONED) return sink.error(id, "UNRESPONSIVE", "The plugin is not responding")
        val call = Call(id, sink)
        if (!inFlight.compareAndSet(null, call)) return sink.error(id, "BUSY", "The plugin is already handling a request")
        call.timer = watchdog.schedule({ abandon(call, "TIMEOUT", "The plugin took too long to respond") }, timeoutMs, TimeUnit.MILLISECONDS)
        call.future = workers.submit { run(call, command!!, args) }
    }

    /** User dismissed or host gave up. A no-op when the request already finished. */
    fun cancel(requestId: String?) {
        val call = inFlight.get() ?: return
        if (call.id == requestId) abandon(call, "CANCELLED", "The request was cancelled")
    }

    fun shutdown() { workers.shutdownNow(); watchdog.shutdownNow() }

    private fun run(call: Call, command: String, args: Map<String, String>) {
        synchronized(call) { call.running = true }
        try {
            val output = handler(command, args) { message ->
                if (!call.done.get()) call.sink.activity(call.id, PluginBounds.bound(message, PluginBounds.MAX_ACTIVITY))
            }
            val problem = PluginBounds.outputProblem(output.title, output.content, output.launch?.label)
            if (problem != null) finish(call) { call.sink.error(call.id, "OUTPUT_TOO_LARGE", problem) }
            else finish(call) {
                output.launch?.let { call.sink.launch(call.id, it.label, it.payload) }
                call.sink.result(call.id, output.title, output.content)
            }
        } catch (e: PluginException) {
            finish(call) { call.sink.error(call.id, PluginBounds.safeErrorCode(e.code), PluginBounds.bound(e.message, PluginBounds.MAX_ERROR_MESSAGE)) }
        } catch (e: InterruptedException) {
            // cancelled or timed out; the reply was already sent
        } catch (t: Throwable) {
            finish(call) { call.sink.error(call.id, "PLUGIN_ERROR", "The plugin failed") } // never leak internals
        } finally {
            synchronized(call) {
                call.running = false
                if (call.abandonedCounted) { call.abandonedCounted = false; abandoned.decrementAndGet() }
            }
        }
    }

    /** Replies at most once and frees the single in-flight slot. */
    private fun finish(call: Call, reply: () -> Unit) {
        if (!call.done.compareAndSet(false, true)) return
        inFlight.compareAndSet(call, null)
        call.timer?.cancel(false)
        reply()
    }

    private fun abandon(call: Call, code: String, message: String) {
        finish(call) {
            call.future?.cancel(true)
            synchronized(call) { if (call.running && !call.abandonedCounted) { call.abandonedCounted = true; abandoned.incrementAndGet() } }
            call.sink.error(call.id, code, message)
        }
    }

    private companion object {
        /** Commands that ignore interruption can pin threads; after this many the plugin is refused. */
        const val MAX_ABANDONED = 2

        fun daemonThreads(name: String) = ThreadFactory { r -> Thread(r, name).apply { isDaemon = true } }
    }
}
