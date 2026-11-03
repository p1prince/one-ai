package com.oneai.app.plugins

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AppControlAdapterTest {
    @Test fun launchesOnlyValidatedSelectedPackage() = runBlocking {
        var launched = ""
        val adapter = AppControlAdapter(AppLauncher { launched = it; true })
        val result = adapter.execute(PluginActionInput(
            actionId = AppControlAdapter.LAUNCH_ACTION,
            capability = "app_launch",
            description = "Launch Calculator",
            values = mapOf("package_name" to "com.example.calculator", "app_label" to "Calculator")
        ))
        assertEquals("com.example.calculator", launched)
        assertEquals("App opened", result.title)
    }

    @Test fun rejectsMalformedPackageName() = runBlocking {
        val adapter = AppControlAdapter(AppLauncher { fail("Must not launch an invalid package"); false })
        try {
            adapter.execute(PluginActionInput(
                actionId = AppControlAdapter.LAUNCH_ACTION,
                capability = "app_launch",
                description = "Launch invalid target",
                values = mapOf("package_name" to "not a package")
            ))
            fail("Expected invalid package rejection")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun reportsUnavailableAppWithoutClaimingSuccess() = runBlocking {
        val adapter = AppControlAdapter(AppLauncher { false })
        val result = adapter.execute(PluginActionInput(
            actionId = AppControlAdapter.LAUNCH_ACTION,
            capability = "app_launch",
            description = "Launch unavailable app",
            values = mapOf("package_name" to "com.example.missing")
        ))
        assertEquals("App not opened", result.title)
    }

    @Test fun rejectsUnsupportedAction() = runBlocking {
        val adapter = AppControlAdapter(AppLauncher { fail("Must not launch"); false })
        try {
            adapter.execute(PluginActionInput("delete_app", "app_launch", "Delete app"))
            fail("Expected unsupported action rejection")
        } catch (_: IllegalArgumentException) { }
    }
}
