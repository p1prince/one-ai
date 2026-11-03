package com.oneai.app.plugins

import android.content.Context
import android.content.Intent
import android.provider.Settings

data class DeviceSettingsAction(val actionId: String, val label: String)

/** Fixed allowlist. The plugin opens Android Settings; it does not change values itself. */
object DeviceControlPolicy {
    private val supported = listOf(
        DeviceSettingsAction("open_wifi_settings", "Wi-Fi"),
        DeviceSettingsAction("open_bluetooth_settings", "Bluetooth"),
        DeviceSettingsAction("open_sound_settings", "Sound & vibration"),
        DeviceSettingsAction("open_display_settings", "Display"),
        DeviceSettingsAction("open_location_settings", "Location")
    )
    fun actions(): List<DeviceSettingsAction> = supported.toList()
    fun action(actionId: String): DeviceSettingsAction? = supported.firstOrNull { it.actionId == actionId }
}

fun interface DeviceSettingsLauncher {
    fun open(actionId: String): Boolean
}

class AndroidDeviceSettingsLauncher(private val context: Context) : DeviceSettingsLauncher {
    override fun open(actionId: String): Boolean {
        val intentAction = when (actionId) {
            "open_wifi_settings" -> Settings.ACTION_WIFI_SETTINGS
            "open_bluetooth_settings" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "open_sound_settings" -> Settings.ACTION_SOUND_SETTINGS
            "open_display_settings" -> Settings.ACTION_DISPLAY_SETTINGS
            "open_location_settings" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            else -> return false
        }
        return runCatching {
            context.startActivity(Intent(intentAction).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
    }
}

/** Trusted adapter hands setting changes to Android's own Settings UI. */
class DeviceControlAdapter(private val launcher: DeviceSettingsLauncher) : PluginAdapter {
    override val manifest = PluginManifest(
        id = PLUGIN_ID,
        name = "Device Actions",
        version = "1.0.0",
        description = "User-approved Android settings and device action integrations.",
        capabilities = listOf("device_actions")
    )

    override suspend fun execute(input: PluginActionInput): PluginActionOutput {
        if (input.capability != "device_actions") throw IllegalArgumentException("Unsupported Device Actions capability")
        val action = DeviceControlPolicy.action(input.actionId)
            ?: throw IllegalArgumentException("Unsupported device settings action")
        if (!launcher.open(action.actionId)) {
            return PluginActionOutput("Settings unavailable", "Android could not open " + action.label + " settings on this device.")
        }
        return PluginActionOutput("Settings opened", "Android " + action.label + " settings opened. Make and confirm any changes in Android Settings.")
    }

    companion object { const val PLUGIN_ID = "one.device.actions" }
}
