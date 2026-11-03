package com.oneai.app.plugins

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/** Android-free validation for package names passed to the app-launch adapter. */
object AppControlPolicy {
    private val packageNamePattern = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")

    fun validPackageName(value: String): Boolean =
        value.length in 3..255 && packageNamePattern.matches(value)
}

data class AppLaunchTarget(val packageName: String, val label: String)

fun interface AppLauncher {
    /** Returns true only when an exported launcher activity was actually started. */
    fun launch(packageName: String): Boolean
}

class AndroidAppLauncher(private val context: Context) : AppLauncher {
    override fun launch(packageName: String): Boolean {
        if (!AppControlPolicy.validPackageName(packageName)) return false
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        if (intent.component?.packageName != packageName) return false
        return runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
    }
}

/** Trusted adapter: this integration launches apps only; it does not inspect their private data. */
class AppControlAdapter(private val launcher: AppLauncher) : PluginAdapter {
    override val manifest = PluginManifest(
        id = PLUGIN_ID,
        name = "App Control",
        version = "1.0.0",
        description = "Find and launch installed Android apps with explicit approval.",
        capabilities = listOf("app_launch")
    )

    override suspend fun execute(input: PluginActionInput): PluginActionOutput {
        if (input.actionId != LAUNCH_ACTION || input.capability != "app_launch") {
            throw IllegalArgumentException("Unsupported App Control action")
        }
        val packageName = input.values["package_name"].orEmpty()
        val label = input.values["app_label"]?.take(80)?.ifBlank { packageName } ?: packageName
        require(AppControlPolicy.validPackageName(packageName)) { "Invalid app package name" }
        if (!launcher.launch(packageName)) {
            return PluginActionOutput("App not opened", "Android could not launch $label. It may no longer be installed or may not expose a launcher activity.")
        }
        return PluginActionOutput("App opened", "ONE AI launched $label. App sign-in and private in-app actions remain under that app's own controls.")
    }

    companion object {
        const val PLUGIN_ID = "one.app.control"
        const val LAUNCH_ACTION = "launch_app"
    }
}

/** Launcher-visible apps only; avoids broad QUERY_ALL_PACKAGES access. */
object InstalledAppCatalog {
    fun load(context: Context): List<AppLaunchTarget> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val manager = context.packageManager
        val activities = if (Build.VERSION.SDK_INT >= 33) {
            manager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            manager.queryIntentActivities(intent, 0)
        }
        return activities.asSequence()
            .mapNotNull { info ->
                val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
                if (packageName == context.packageName || !AppControlPolicy.validPackageName(packageName)) return@mapNotNull null
                val label = info.loadLabel(manager)?.toString()?.trim().orEmpty().ifBlank { packageName }
                AppLaunchTarget(packageName, label)
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
            .take(60)
            .toList()
    }
}
