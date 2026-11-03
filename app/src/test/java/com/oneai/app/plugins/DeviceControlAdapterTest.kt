package com.oneai.app.plugins

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DeviceControlAdapterTest {
    @Test fun opensOnlyAllowlistedSettingsAction() = runBlocking {
        var opened = ""
        val adapter = DeviceControlAdapter(DeviceSettingsLauncher { opened = it; true })
        val result = adapter.execute(PluginActionInput(
            actionId = "open_wifi_settings",
            capability = "device_actions",
            description = "Open Android Wi-Fi settings"
        ))
        assertEquals("open_wifi_settings", opened)
        assertEquals("Settings opened", result.title)
    }

    @Test fun rejectsUnknownActionBeforeCallingLauncher() = runBlocking {
        val adapter = DeviceControlAdapter(DeviceSettingsLauncher { fail("Unknown action must not reach launcher"); false })
        try {
            adapter.execute(PluginActionInput("toggle_wifi", "device_actions", "Toggle Wi-Fi"))
            fail("Expected unsupported action rejection")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun rejectsWrongCapability() = runBlocking {
        val adapter = DeviceControlAdapter(DeviceSettingsLauncher { fail("Wrong capability must not reach launcher"); false })
        try {
            adapter.execute(PluginActionInput("open_wifi_settings", "app_launch", "Open Wi-Fi settings"))
            fail("Expected unsupported capability rejection")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun reportsUnavailableSettingsWithoutClaimingSuccess() = runBlocking {
        val adapter = DeviceControlAdapter(DeviceSettingsLauncher { false })
        val result = adapter.execute(PluginActionInput("open_location_settings", "device_actions", "Open Location settings"))
        assertEquals("Settings unavailable", result.title)
    }
}
