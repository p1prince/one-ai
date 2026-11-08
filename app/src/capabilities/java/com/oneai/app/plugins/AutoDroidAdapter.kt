package com.oneai.app.plugins

import android.content.Context
import android.content.Intent

object AutoDroidIntegrationPolicy {
    const val PACKAGE_NAME = "com.aditsyal.autodroid"
    const val OPEN_ACTION = "open_autodroid"
}

fun interface AutoDroidLauncher {
    fun open(): Boolean
}

class AndroidAutoDroidLauncher(private val context: Context) : AutoDroidLauncher {
    override fun open(): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(AutoDroidIntegrationPolicy.PACKAGE_NAME)
            ?: return false
        if (intent.component?.packageName != AutoDroidIntegrationPolicy.PACKAGE_NAME) return false
        return runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
    }
}

/** Opens the separate app only; macro selection and execution remain in AutoDroid. */
class AutoDroidAdapter(private val launcher: AutoDroidLauncher) : PluginAdapter {
    override val manifest = PluginManifest(
        id = PLUGIN_ID,
        name = "Automation Engine",
        version = "1.0.0",
        description = "A safe integration point for automation engines such as AutoDroid.",
        capabilities = listOf("accessibility", "automation")
    )

    override suspend fun execute(input: PluginActionInput): PluginActionOutput {
        if (input.actionId != AutoDroidIntegrationPolicy.OPEN_ACTION ||
            input.capability != "automation" || input.values.isNotEmpty()) {
            throw IllegalArgumentException("Unsupported AutoDroid integration action")
        }
        if (!launcher.open()) {
            return PluginActionOutput(
                "AutoDroid unavailable",
                "AutoDroid is not installed or its launcher activity is unavailable."
            )
        }
        return PluginActionOutput(
            "AutoDroid opened",
            "AutoDroid is open. Select and run a macro there. ONE AI did not start a macro or transfer macro data."
        )
    }

    companion object {
        const val PLUGIN_ID = "one.automation.engine"
    }
}
