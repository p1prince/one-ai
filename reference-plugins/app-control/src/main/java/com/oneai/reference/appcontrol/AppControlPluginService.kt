package com.oneai.reference.appcontrol

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.oneai.plugin.sdk.CallContext
import com.oneai.plugin.sdk.HostVerifier
import com.oneai.plugin.sdk.OneAiPluginService
import com.oneai.plugin.sdk.PluginException
import com.oneai.plugin.sdk.PluginOutput
import com.oneai.plugin.sdk.screenLaunch

/** Independently installed App Control reference plugin. It uses only the published ONE AI SDK contract. */
class AppControlPluginService : OneAiPluginService() {
    private val commands = AppControlCommands()

    override fun onCommand(command: String, args: Map<String, String>, context: CallContext): PluginOutput =
        commands.execute(command, args, ::createLaunchRequest)

    override fun onView(serviceId: String): String? =
        if (serviceId == AppControlBubbleView.SERVICE_ID) {
            AppControlBubbleView.render(loadInstalledApps())
        } else null

    /** Debug builds can be tested with a debug ONE AI host; release builds fail closed until host signing is configured. */
    override fun hostVerifier(): HostVerifier =
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) HostVerifier(allowAnyCertificate = true)
        else HostVerifier()

    private fun createLaunchRequest(packageName: String, label: String): PluginOutput {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: throw PluginException("APP_UNAVAILABLE", "That app is no longer available")
        if (launchIntent.component?.packageName != packageName) {
            throw PluginException("APP_UNAVAILABLE", "Android did not provide a launcher activity for that app")
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            packageName.hashCode() and 0x7fffffff,
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return PluginOutput(
            "Launch approval requested",
            "ONE AI will ask you before opening $label.",
            screenLaunch("Open $label", pendingIntent)
        )
    }

    /** Launcher-visible apps only; the plugin requests no broad QUERY_ALL_PACKAGES access. */
    private fun loadInstalledApps(): List<AppLaunchTarget> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val manager = packageManager
        val activities = if (Build.VERSION.SDK_INT >= 33) {
            manager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            manager.queryIntentActivities(intent, 0)
        }
        return activities.asSequence()
            .mapNotNull { info ->
                val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
                if (packageName == this.packageName || !AppControlPolicy.validPackageName(packageName)) return@mapNotNull null
                val label = runCatching { info.loadLabel(manager)?.toString()?.trim().orEmpty() }
                    .getOrDefault("")
                    .ifBlank { packageName }
                AppLaunchTarget(packageName, label)
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
            .take(60)
            .toList()
    }
}
