package com.oneai.app.plugins

import com.oneai.app.builtin.BuiltInBubbleProviders
import com.oneai.app.core.CoreReservedNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Runs only in the `capabilities` flavor (D9). Keeps the Core reserved names in step with the legacy
 * catalogue that now lives with the capability code, and checks the retired reference-plugin ids stay unregistered.
 */
class CapabilityCatalogueIsolationTest {
    @Test fun reservedPluginIdsEqualTheLegacyCatalogueIds() {
        assertEquals(BuiltInCatalogue.templates.map { it.id }.toSet(), CoreReservedNames.pluginIds)
    }

    @Test fun reservedBubbleServicesEqualTheLegacyCatalogueServices() {
        assertEquals(BuiltInCatalogue.bubbleBindings.values.toSet(), CoreReservedNames.bubbleServices)
    }

    @Test fun referencePluginsAreNotReintroducedAsInAppBuiltins() {
        assertFalse(BuiltInCatalogue.templates.any { it.id == "com.oneai.reference.appcontrol" || it.id == "one.app.control" })
        assertFalse("app_control" in BuiltInCatalogue.bubbleBindings.values)
        assertFalse(BuiltInCatalogue.templates.any { it.id == "one.device.actions" })
        assertFalse("device_control" in BuiltInCatalogue.bubbleBindings.values)
        assertFalse(BuiltInBubbleProviders.bindings.containsKey("one.device.actions"))
    }
}
