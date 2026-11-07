package com.oneai.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginStartupAccessPolicyTest {
    @Test fun bubbleAccessWaitsForRestorationAndAnEnabledPlugin() {
        assertFalse(PluginStartupAccessPolicy.canChangeBubbleAccess(restorationReady = false, pluginEnabled = true))
        assertFalse(PluginStartupAccessPolicy.canChangeBubbleAccess(restorationReady = true, pluginEnabled = false))
        assertTrue(PluginStartupAccessPolicy.canChangeBubbleAccess(restorationReady = true, pluginEnabled = true))
    }

    @Test fun developerPluginCreationWaitsForRestorationAndValidEnabledInputs() {
        assertFalse(PluginStartupAccessPolicy.canCreateDeveloperPlugin(false, true, "dev.example.plugin", "Example"))
        assertFalse(PluginStartupAccessPolicy.canCreateDeveloperPlugin(true, false, "dev.example.plugin", "Example"))
        assertFalse(PluginStartupAccessPolicy.canCreateDeveloperPlugin(true, true, "", "Example"))
        assertFalse(PluginStartupAccessPolicy.canCreateDeveloperPlugin(true, true, "dev.example.plugin", "  "))
        assertTrue(PluginStartupAccessPolicy.canCreateDeveloperPlugin(true, true, "dev.example.plugin", "Example"))
    }
}
