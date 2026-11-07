package com.oneai.app.core

import com.oneai.app.plugins.BuiltInCatalogue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Stage 9.7b: the reserved names keep their exact previous values and no longer come from the built-in catalogue. */
class CoreReservedNamesTest {
    private fun source(relativePath: String): String {
        val candidates = listOf(File("src/main/java/$relativePath"), File("app/src/main/java/$relativePath"))
        return candidates.firstOrNull { it.isFile }?.readText() ?: error("Could not locate production source: $relativePath")
    }

    @Test fun reservedPluginIdsEqualTheLegacyCatalogueIds() {
        assertEquals(BuiltInCatalogue.templates.map { it.id }.toSet(), CoreReservedNames.pluginIds)
    }

    @Test fun reservedBubbleServicesEqualTheLegacyCatalogueServices() {
        assertEquals(BuiltInCatalogue.bubbleBindings.values.toSet(), CoreReservedNames.bubbleServices)
    }

    @Test fun reservedNamesAreNotEmpty() {
        assertTrue(CoreReservedNames.pluginIds.isNotEmpty())
        assertTrue(CoreReservedNames.bubbleServices.isNotEmpty())
    }

    @Test fun productionCompositionUsesTheExplicitReservationAndNotTheCatalogue() {
        val appServices = source("com/oneai/app/AppServices.kt")
        assertFalse("AppServices must not depend on the legacy built-in catalogue.", appServices.contains("BuiltInCatalogue"))
        assertTrue(appServices.contains("reservedIds = CoreReservedNames.pluginIds"))
        assertTrue(appServices.contains("CoreReservedNames.bubbleServices"))
    }
}
