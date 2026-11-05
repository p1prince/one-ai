package com.oneai.reference.devicecontrol

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.provider.Settings
import com.oneai.plugin.sdk.CallContext
import com.oneai.plugin.sdk.HostVerifier
import com.oneai.plugin.sdk.OneAiPluginService
import com.oneai.plugin.sdk.PluginException
import com.oneai.plugin.sdk.PluginOutput
import com.oneai.plugin.sdk.screenLaunch

/** Separately installed Device Control reference plugin; imports only the public ONE AI SDK. */
class DeviceControlPluginService : OneAiPluginService() {
    private val commands = DeviceControlCommands()

    override fun onCommand(command: String, args: Map<String, String>, context: CallContext): PluginOutput =
        commands.execute(command, ::createSettingsRequest)

    override fun onView(serviceId: String): String? =
        if (serviceId == DeviceControlBubbleView.SERVICE_ID) DeviceControlBubbleView.render() else null

    /** Debug builds can use a debug host; release builds fail closed until Stage 12 signing is configured. */
    override fun hostVerifier(): HostVerifier =
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) HostVerifier(allowAnyCertificate = true)
        else HostVerifier()

    private fun createSettingsRequest(action: DeviceSettingsAction): PluginOutput {
        val settingsAction = when (action.id) {
            "open_wifi_settings" -> Settings.ACTION_WIFI_SETTINGS
            "open_bluetooth_settings" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "open_sound_settings" -> Settings.ACTION_SOUND_SETTINGS
            "open_display_settings" -> Settings.ACTION_DISPLAY_SETTINGS
            "open_location_settings" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            else -> throw PluginException("UNKNOWN_COMMAND", "Device Control does not support that settings action")
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            action.id.hashCode() and 0x7fffffff,
            Intent(settingsAction).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return PluginOutput(
            "Settings launch approval requested",
            "ONE AI will ask before opening Android ${action.label} settings.",
            screenLaunch("Open ${action.label} settings", pendingIntent)
        )
    }
}
