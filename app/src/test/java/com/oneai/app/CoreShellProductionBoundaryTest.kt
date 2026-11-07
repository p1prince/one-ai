package com.oneai.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CoreShellProductionBoundaryTest {
    private fun source(relativePath: String): String {
        val candidates = listOf(
            File("src/main/java/$relativePath"),
            File("app/src/main/java/$relativePath")
        )
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: error("Could not locate production source: $relativePath")
    }

    @Test
    fun productionCompositionStartsWithAnEmptyOptionalPluginCatalogue() {
        val appServices = source("com/oneai/app/AppServices.kt")
        assertTrue(
            "The real production host must start with an empty optional plugin catalogue.",
            assertTrue(
            "The real production host must start with an empty optional plugin catalogue.",
            appServices.contains("PluginHostComposition(") &&
                appServices.contains("store, emptyList(), BubbleProviders.registry")
        )
        )
        assertTrue(
            "The generic saved-command entry point must remain a core command.",
            appServices.contains("commandRegistry.registerCore(\\\"run_saved\\\"")
        )
    }

    @Test
    fun productionCompositionDoesNotRegisterInterimCapabilityProviders() {
        val appServices = source("com/oneai/app/AppServices.kt")
        assertFalse(
            "Optional built-in Bubble providers must not be registered during shell startup.",
            appServices.contains("BuiltInBubbleProviders.registerAll")
        )
    }

    @Test
    fun commandOneHomeCopyDescribesTheWorkingAiInterface() {
        val mainActivity = source("com/oneai/app/MainActivity.kt")
        assertTrue(mainActivity.contains("Ask ONE AI to help with your next task."))
        assertFalse(mainActivity.contains("Your AI command interface is being prepared."))
    }
}
