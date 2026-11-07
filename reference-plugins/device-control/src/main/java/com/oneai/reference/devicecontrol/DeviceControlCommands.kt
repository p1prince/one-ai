package com.oneai.reference.devicecontrol

import com.oneai.plugin.sdk.PluginException
import com.oneai.plugin.sdk.PluginOutput

data class DeviceSettingsAction(val id: String, val label: String)

/** Fixed allowlist. The plugin can open Android Settings screens, never silently toggle settings. */
object DeviceControlPolicy {
    private val supported = listOf(
        DeviceSettingsAction("open_wifi_settings", "Wi-Fi"),
        DeviceSettingsAction("open_bluetooth_settings", "Bluetooth"),
        DeviceSettingsAction("open_sound_settings", "Sound & vibration"),
        DeviceSettingsAction("open_display_settings", "Display"),
        DeviceSettingsAction("open_location_settings", "Location")
    )

    fun actions(): List<DeviceSettingsAction> = supported.toList()
    fun action(id: String): DeviceSettingsAction? = supported.firstOrNull { it.id == id }
}

class DeviceControlCommands {
    fun execute(command: String, requestOpen: (DeviceSettingsAction) -> PluginOutput): PluginOutput {
        val action = DeviceControlPolicy.action(command)
            ?: throw PluginException("UNKNOWN_COMMAND", "Device Control does not support that settings action")
        return requestOpen(action)
    }
}
