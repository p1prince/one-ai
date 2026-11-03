package com.oneai.app.plugins

import org.junit.Assert.*
import org.junit.Test

class DeviceControlPolicyTest {
    @Test fun exposesOnlySupportedSettingsDestinations() {
        val actions = DeviceControlPolicy.actions()
        assertEquals(
            setOf("open_wifi_settings", "open_bluetooth_settings", "open_sound_settings", "open_display_settings", "open_location_settings"),
            actions.map { it.actionId }.toSet()
        )
        assertEquals(5, actions.size)
    }

    @Test fun rejectsUnknownOrDirectToggleActions() {
        listOf("toggle_wifi", "enable_bluetooth", "change_brightness", "launch_arbitrary_intent", "")
            .forEach { assertNull(it, DeviceControlPolicy.action(it)) }
    }

    @Test fun returnsUserFacingLabelsForKnownActions() {
        assertEquals("Wi-Fi", DeviceControlPolicy.action("open_wifi_settings")?.label)
        assertEquals("Location", DeviceControlPolicy.action("open_location_settings")?.label)
    }
}
