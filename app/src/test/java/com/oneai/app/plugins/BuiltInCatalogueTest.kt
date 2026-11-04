package com.oneai.app.plugins

import com.oneai.app.builtin.BuiltInBubbleProviders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltInCatalogueTest {
    @Test fun everyTemplateProducesAValidManifest() {
        BuiltInCatalogue.templates.forEach { t ->
            val result = PluginManifestValidator.validate(
                PluginManifest(id = t.id, name = t.name, version = t.version, description = t.description,
                    capabilities = t.capabilities, dependencies = t.dependencies)
            )
            assertTrue("${t.id}: ${result.exceptionOrNull()?.message}", result.isSuccess)
        }
    }

    @Test fun pluginIdsAndBubbleServicesAreUnique() {
        val ids = BuiltInCatalogue.templates.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        val services = BuiltInCatalogue.bubbleBindings.values.toList()
        assertEquals(services.size, services.toSet().size)
    }

    @Test fun bindingsAreDerivedOnlyFromTemplatesAndNeverCollideWithTheCoreService() {
        assertTrue(BuiltInCatalogue.bubbleBindings.keys.all { key -> BuiltInCatalogue.templates.any { it.id == key } })
        assertTrue("command_manager" !in BuiltInCatalogue.bubbleBindings.values)
    }

    @Test fun appControlAndDeviceControlHaveBubbleBindingsSoSettingsCanEnableThem() {
        assertEquals("app_control", BuiltInCatalogue.bubbleBindings["one.app.control"])
        assertEquals("device_control", BuiltInCatalogue.bubbleBindings["one.device.actions"])
    }

    @Test fun interimBubbleProvidersStayInStepWithTheCatalogueBindings() {
        assertEquals(BuiltInCatalogue.bubbleBindings, BuiltInBubbleProviders.bindings)
    }
}
