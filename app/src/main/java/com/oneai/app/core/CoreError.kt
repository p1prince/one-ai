package com.oneai.app.core

import com.oneai.app.ai.AiProviderException
import com.oneai.app.commands.CommandException

enum class CoreErrorKind { PROVIDER, COMMAND, PLUGIN, SERVICE, UNEXPECTED }

/** Normalised, user-safe error. [message] never contains raw exception text for unexpected failures. */
data class CoreError(val kind: CoreErrorKind, val code: String, val message: String, val recoverable: Boolean)

object CoreErrors {
    /**
     * Maps any failure to a [CoreError]. Callers must rethrow `CancellationException` before
     * calling this; cancellation is control flow, not an error.
     */
    fun from(error: Throwable): CoreError = when (error) {
        is AiProviderException -> CoreError(CoreErrorKind.PROVIDER, error.code, error.message ?: "AI provider error", true)
        is CommandException -> CoreError(CoreErrorKind.COMMAND, error.code, error.message ?: "Command error", true)
        else -> CoreError(CoreErrorKind.UNEXPECTED, "UNEXPECTED", "Something went wrong. Check the activity log.", false)
    }

    fun service(code: String, message: String) = CoreError(CoreErrorKind.SERVICE, code, message, true)
    fun plugin(code: String, message: String) = CoreError(CoreErrorKind.PLUGIN, code, message, true)
}
