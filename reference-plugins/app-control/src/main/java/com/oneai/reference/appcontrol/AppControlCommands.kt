package com.oneai.reference.appcontrol

import com.oneai.plugin.sdk.PluginException
import com.oneai.plugin.sdk.PluginOutput

/** Android-free command policy shared by the reference plugin's service and JVM tests. */
object AppControlPolicy {
    private val packageNamePattern = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")

    fun validPackageName(value: String): Boolean =
        value.length in 3..255 && packageNamePattern.matches(value)
}

data class AppLaunchTarget(val packageName: String, val label: String)

/**
 * Validates the host-supplied selection before asking the Android boundary to create a PendingIntent.
 * The plugin never starts another app directly from its background service.
 */
class AppControlCommands {
    fun execute(
        command: String,
        args: Map<String, String>,
        requestLaunch: (packageName: String, label: String) -> PluginOutput
    ): PluginOutput {
        if (command != LAUNCH_COMMAND) {
            throw PluginException("UNKNOWN_COMMAND", "App Control does not support that command")
        }
        val packageName = args["package_name"]?.trim().orEmpty()
        if (!AppControlPolicy.validPackageName(packageName)) {
            throw PluginException("INVALID_PACKAGE", "Choose a valid installed app")
        }
        val label = args["app_label"]?.trim()?.take(MAX_LABEL)?.ifBlank { packageName } ?: packageName
        return requestLaunch(packageName, label)
    }

    companion object {
        const val LAUNCH_COMMAND = "launch_app"
        const val MAX_LABEL = 80
    }
}
