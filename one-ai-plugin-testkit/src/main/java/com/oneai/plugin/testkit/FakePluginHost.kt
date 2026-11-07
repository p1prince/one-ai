package com.oneai.plugin.testkit

import com.oneai.plugin.sdk.CallContext
import com.oneai.plugin.sdk.PluginBounds
import com.oneai.plugin.sdk.PluginCallRunner
import com.oneai.plugin.sdk.PluginConfig
import com.oneai.plugin.sdk.PluginConfigHolder
import com.oneai.plugin.sdk.PluginOutput
import com.oneai.plugin.sdk.ReplySink
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * An in-memory host harness for testing a plugin's command handler without Android or a device.
 *
 * It exercises the same SDK request runner used by OneAiPluginService: input validation, bounds,
 * timeout/cancellation, progress, screen-launch ordering, and exactly-once terminal replies.
 * It deliberately does not simulate Binder identity, PackageManager signatures, Android process
 * isolation, host trust/approval policy, or UI. Those require host integration and Stage 11 tests.
 */
class FakePluginHost private constructor(
    timeoutMs: Long,
    private val configHolder: PluginConfigHolder,
    handler: (command: String, args: Map<String, String>, context: CallContext, config: PluginConfig) -> PluginOutput
) : AutoCloseable {
    /** For a plugin that does not use settings. */
    constructor(
        timeoutMs: Long = PluginBounds.DEFAULT_TIMEOUT_MS,
        handler: (command: String, args: Map<String, String>, context: CallContext) -> PluginOutput
    ) : this(timeoutMs, PluginConfigHolder(), { command, args, context, _ -> handler(command, args, context) })

    private val runner = PluginCallRunner(
        timeoutMs = timeoutMs,
        handler = { command, args, context -> handler(command, args, context, configHolder.current) }
    )

    /**
     * Plugin API level 3. Delivers a configuration snapshot the way ONE AI does: bounded and replaced whole. A
     * snapshot that breaks a bound (more than 24 settings, a bad key, a value over 500 characters, over 8 KB in
     * total) is rejected and the previous snapshot stays; the failure is returned so a test can assert it. Call it
     * before [execute], as the host does.
     */
    fun applyConfig(schemaVersion: Int, values: Map<String, String>): Result<PluginConfig> {
        val entries = values.entries.toList()
        return configHolder.apply(schemaVersion, entries.map { it.key }, entries.map { it.value })
    }

    /** The snapshot a command would see right now ([PluginConfig.EMPTY] until one is applied). */
    val config: PluginConfig get() = configHolder.current

    /**
     * Sends one command to the in-memory plugin endpoint. Use a fresh request ID for each call.
     * The SDK permits only one request in flight per plugin instance.
     */
    fun execute(
        requestId: String,
        command: String,
        args: Map<String, String> = emptyMap()
    ): PluginTestCall {
        val sink = RecordingSink()
        val entries = args.entries.toList()
        runner.execute(requestId, command, entries.map { it.key }, entries.map { it.value }, sink)
        return PluginTestCall(requestId, sink)
    }

    /** Cancels the matching in-flight request, using the SDK's normal cancellation path. */
    fun cancel(requestId: String) = runner.cancel(requestId)

    override fun close() = runner.shutdown()

    companion object {
        /**
         * For a plugin whose commands read [PluginConfig]. The handler receives the snapshot that was current when
         * the command started, like a command inside `OneAiPluginService` reading its `config` property.
         */
        fun withConfig(
            timeoutMs: Long = PluginBounds.DEFAULT_TIMEOUT_MS,
            handler: (command: String, args: Map<String, String>, context: CallContext, config: PluginConfig) -> PluginOutput
        ): FakePluginHost = FakePluginHost(timeoutMs, PluginConfigHolder(), handler)
    }

    internal class RecordingSink : ReplySink {
        private val terminal = CountDownLatch(1)
        val events = CopyOnWriteArrayList<PluginTestEvent>()

        override fun result(requestId: String, title: String, content: String) {
            events += PluginTestEvent.Completed(requestId, title, content)
            terminal.countDown()
        }

        override fun error(requestId: String, code: String, message: String) {
            events += PluginTestEvent.Failed(requestId, code, message)
            terminal.countDown()
        }

        override fun activity(requestId: String, message: String) {
            events += PluginTestEvent.Progress(requestId, message)
        }

        override fun launch(requestId: String, label: String, payload: Any) {
            events += PluginTestEvent.Launch(requestId, label, payload)
        }

        fun await(timeoutMs: Long): Boolean = terminal.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    class PluginTestCall internal constructor(
        val requestId: String,
        private val sink: RecordingSink
    ) {
        /** A thread-safe snapshot, including progress/launch events received so far. */
        val events: List<PluginTestEvent> get() = sink.events.toList()

        /**
         * Waits for a terminal result or error and returns every event in delivery order.
         * Throws TimeoutException if the command has not completed within [timeoutMs].
         */
        fun await(timeoutMs: Long = DEFAULT_AWAIT_TIMEOUT_MS): List<PluginTestEvent> {
            if (!sink.await(timeoutMs)) throw TimeoutException("Plugin request $requestId did not complete in time")
            return events
        }

        private companion object {
            const val DEFAULT_AWAIT_TIMEOUT_MS = 5_000L
        }
    }
}

/** Observable events emitted by the SDK runner. A launch payload remains opaque to the test kit. */
sealed interface PluginTestEvent {
    val requestId: String

    data class Progress(override val requestId: String, val message: String) : PluginTestEvent
    data class Launch(override val requestId: String, val label: String, val payload: Any) : PluginTestEvent
    data class Completed(override val requestId: String, val title: String, val content: String) : PluginTestEvent
    data class Failed(override val requestId: String, val code: String, val message: String) : PluginTestEvent
}
