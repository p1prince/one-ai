package com.oneai.app.plugins

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AutoDroidAdapterTest {
    @Test fun opensOnlyTheAutoDroidWorkspaceAction() = runBlocking {
        var opened = false
        val adapter = AutoDroidAdapter(AutoDroidLauncher { opened = true; true })
        val result = adapter.execute(PluginActionInput(
            actionId = AutoDroidIntegrationPolicy.OPEN_ACTION,
            capability = "automation",
            description = "Open AutoDroid automation workspace"
        ))
        assertTrue(opened)
        assertEquals("AutoDroid opened", result.title)
        assertTrue(result.content.contains("did not start a macro"))
    }

    @Test fun rejectsUnknownActionsBeforeOpeningAutoDroid() = runBlocking {
        val adapter = AutoDroidAdapter(AutoDroidLauncher { fail("Unknown action must not open AutoDroid"); false })
        try {
            adapter.execute(PluginActionInput("run_macro", "automation", "Run macro"))
            fail("Expected unsupported action rejection")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun rejectsWrongCapabilityBeforeOpeningAutoDroid() = runBlocking {
        val adapter = AutoDroidAdapter(AutoDroidLauncher { fail("Wrong capability must not open AutoDroid"); false })
        try {
            adapter.execute(PluginActionInput(
                AutoDroidIntegrationPolicy.OPEN_ACTION,
                "accessibility",
                "Open AutoDroid"
            ))
            fail("Expected unsupported capability rejection")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun rejectsCallerSuppliedMacroData() = runBlocking {
        val adapter = AutoDroidAdapter(AutoDroidLauncher { fail("Macro data must not cross this boundary"); false })
        try {
            adapter.execute(PluginActionInput(
                AutoDroidIntegrationPolicy.OPEN_ACTION,
                "automation",
                "Open AutoDroid",
                mapOf("macro_id" to "42")
            ))
            fail("Expected unsupported input rejection")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun reportsMissingAutoDroidWithoutClaimingSuccess() = runBlocking {
        val adapter = AutoDroidAdapter(AutoDroidLauncher { false })
        val result = adapter.execute(PluginActionInput(
            AutoDroidIntegrationPolicy.OPEN_ACTION,
            "automation",
            "Open AutoDroid automation workspace"
        ))
        assertEquals("AutoDroid unavailable", result.title)
    }
}
