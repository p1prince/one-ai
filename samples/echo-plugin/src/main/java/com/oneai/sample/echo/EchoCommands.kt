package com.oneai.sample.echo

import com.oneai.plugin.sdk.CallContext
import com.oneai.plugin.sdk.PluginException
import com.oneai.plugin.sdk.PluginOutput

/**
 * What the Echo plugin actually does. It has no Android dependency, so it is unit-tested on the JVM; the
 * SDK base class supplies everything security-relevant (caller check, bounds, deadline, one request at a time).
 *
 * Rules a plugin command should follow, all shown here:
 *  - validate every argument yourself and fail with a [PluginException] whose message is safe to show a user;
 *  - report progress with [CallContext.reportActivity] (bounded, never secrets);
 *  - stay cancellable: let [InterruptedException] escape (the SDK interrupts the worker on cancel or timeout).
 */
class EchoCommands(
    /** Injected so tests do not really sleep. */
    private val sleep: (millis: Long) -> Unit = { Thread.sleep(it) }
) {
    fun run(command: String, args: Map<String, String>, context: CallContext): PluginOutput = when (command) {
        "echo" -> PluginOutput("Echo", requiredText(args))
        "reverse" -> PluginOutput("Reversed", requiredText(args).reversed())
        "wait" -> waitThenEcho(args, context)
        else -> throw PluginException("UNKNOWN_COMMAND", "This plugin has no command with that name")
    }

    private fun waitThenEcho(args: Map<String, String>, context: CallContext): PluginOutput {
        val seconds = args["seconds"]?.trim()?.toIntOrNull()
        if (seconds == null || seconds !in 1..MAX_WAIT_SECONDS) {
            throw PluginException("INVALID_ARGUMENT", "seconds must be a whole number from 1 to $MAX_WAIT_SECONDS")
        }
        val text = requiredText(args)
        for (elapsed in 1..seconds) {
            sleep(1_000)
            context.reportActivity("Waited $elapsed of $seconds s")
        }
        return PluginOutput("Waited", text)
    }

    private fun requiredText(args: Map<String, String>): String {
        val text = args["text"]
        if (text.isNullOrBlank()) throw PluginException("INVALID_ARGUMENT", "text is required")
        if (text.length > MAX_TEXT) throw PluginException("INVALID_ARGUMENT", "text must be $MAX_TEXT characters or fewer")
        return text
    }

    companion object {
        const val MAX_TEXT = 1_000
        /** Deliberately above the SDK's 30 s default deadline so the timeout path can be exercised. */
        const val MAX_WAIT_SECONDS = 60
    }
}
