package com.oneai.sample.echo

import com.oneai.plugin.sdk.CallContext
import com.oneai.plugin.sdk.PluginConfig
import com.oneai.plugin.sdk.PluginException
import com.oneai.plugin.sdk.PluginOutput

/**
 * What the Echo plugin actually does. It has no Android dependency, so it is unit-tested on the JVM; the
 * SDK base class supplies everything security-relevant (caller check, bounds, deadline, one request at a time).
 *
 * Rules a plugin command should follow, all shown here:
 *  - validate every argument yourself and fail with a [PluginException] whose message is safe to show a user;
 *  - report progress with [CallContext.reportActivity] (bounded, never secrets);
 *  - stay cancellable: let [InterruptedException] escape (the SDK interrupts the worker on cancel or timeout);
 *  - read the user's settings from the [PluginConfig] passed in on every call (ONE AI delivers a fresh snapshot
 *    before each command), and cope with a missing value, because a setting may have neither a value nor a default.
 */
class EchoCommands(
    /** Injected so tests do not really sleep. */
    private val sleep: (millis: Long) -> Unit = { Thread.sleep(it) }
) {
    fun run(command: String, args: Map<String, String>, context: CallContext, config: PluginConfig = PluginConfig.EMPTY): PluginOutput = when (command) {
        "echo" -> PluginOutput("Echo", styled(requiredText(args), config))
        "reverse" -> PluginOutput("Reversed", styled(requiredText(args).reversed(), config))
        "wait" -> waitThenEcho(args, context, config)
        else -> throw PluginException("UNKNOWN_COMMAND", "This plugin has no command with that name")
    }

    private fun waitThenEcho(args: Map<String, String>, context: CallContext, config: PluginConfig): PluginOutput {
        val seconds = args["seconds"]?.trim()?.toIntOrNull()
        if (seconds == null || seconds !in 1..MAX_WAIT_SECONDS) {
            throw PluginException("INVALID_ARGUMENT", "seconds must be a whole number from 1 to $MAX_WAIT_SECONDS")
        }
        val text = requiredText(args)
        for (elapsed in 1..seconds) {
            sleep(1_000)
            context.reportActivity("Waited $elapsed of $seconds s")
        }
        return PluginOutput("Waited", styled(text, config))
    }

    /** Applies the user's settings: optional uppercase, then the optional prefix. Both fall back to "no change". */
    private fun styled(text: String, config: PluginConfig): String {
        val body = if (config.boolean(KEY_UPPERCASE)) text.uppercase() else text
        return config.string(KEY_PREFIX) + body
    }

    private fun requiredText(args: Map<String, String>): String {
        val text = args["text"]
        if (text.isNullOrBlank()) throw PluginException("INVALID_ARGUMENT", "text is required")
        if (text.length > MAX_TEXT) throw PluginException("INVALID_ARGUMENT", "text must be $MAX_TEXT characters or fewer")
        return text
    }

    companion object {
        /** Keys declared in `res/xml/one_ai_plugin.xml`; a unit test keeps the two in step. */
        const val KEY_PREFIX = "prefix"
        const val KEY_UPPERCASE = "uppercase"
        const val MAX_TEXT = 1_000
        /** Deliberately above the SDK's 30 s default deadline so the timeout path can be exercised. */
        const val MAX_WAIT_SECONDS = 60
    }
}
