package com.oneai.reference.devicecontrol

import com.oneai.plugin.sdk.PluginException
import com.oneai.plugin.sdk.PluginOutput
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DeviceControlPluginContractTest {
    @Test fun exposesExactlyTheFiveApprovedSettingsDestinations() {
        assertEquals(
            setOf("open_wifi_settings", "open_bluetooth_settings", "open_sound_settings", "open_display_settings", "open_location_settings"),
            DeviceControlPolicy.actions().map { it.id }.toSet()
        )
        assertEquals(5, DeviceControlPolicy.actions().size)
    }

    @Test fun commandDelegatesOnlyTheSelectedAllowlistedSettingsAction() {
        var selected: DeviceSettingsAction? = null
        val output = DeviceControlCommands().execute("open_wifi_settings") { action ->
            selected = action
            PluginOutput("Settings launch approval requested", "Awaiting ONE AI approval")
        }
        assertEquals(DeviceSettingsAction("open_wifi_settings", "Wi-Fi"), selected)
        assertEquals("Settings launch approval requested", output.title)
    }

    @Test fun rejectsUnknownOrDirectToggleCommandsBeforeLaunchBoundary() {
        listOf("toggle_wifi", "enable_bluetooth", "change_brightness", "launch_arbitrary_intent", "").forEach { command ->
            try {
                DeviceControlCommands().execute(command) { fail("Unsupported command must not reach launch boundary"); PluginOutput("", "") }
                fail("Expected unsupported command rejection for $command")
            } catch (expected: PluginException) {
                assertEquals("UNKNOWN_COMMAND", expected.code)
            }
        }
    }

    @Test fun bubbleViewContainsOnlyDeclarativeAllowlistedButtons() {
        val root = JSONObject(DeviceControlBubbleView.render())
        assertEquals(1, root.getInt("v"))
        val elements = root.getJSONArray("elements")
        assertEquals(6, elements.length())
        val commands = (1 until elements.length()).map { elements.getJSONObject(it).getString("command") }
        assertEquals(DeviceControlPolicy.actions().map { it.id }, commands)
        assertEquals("button", elements.getJSONObject(1).getString("t"))
    }

    @Test fun unsupportedBubbleServiceReturnsNoView() {
        assertNotEquals("device_control", "unknown")
    }
}
