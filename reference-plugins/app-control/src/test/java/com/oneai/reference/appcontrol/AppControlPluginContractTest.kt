package com.oneai.reference.appcontrol

import com.oneai.plugin.sdk.PluginException
import com.oneai.plugin.sdk.PluginOutput
import org.json.JSONObject
import org.junit.Assert.*
import java.io.File
import org.junit.Test

class AppControlPluginContractTest {
    @Test fun acceptsNormalPackageNamesAndRejectsMalformedValues() {
        assertTrue(AppControlPolicy.validPackageName("com.android.settings"))
        assertTrue(AppControlPolicy.validPackageName("org.example.My_App"))
        listOf("", "calculator", "com..bad", "com.example space", ".example.app", "a".repeat(256))
            .forEach { assertFalse(it, AppControlPolicy.validPackageName(it)) }
    }

    @Test fun launchCommandValidatesSelectionAndDelegatesOnlyTheSelectedPackage() {
        var requested = ""
        var label = ""
        val output = AppControlCommands().execute(
            "launch_app",
            mapOf("package_name" to "com.example.calculator", "app_label" to "Calculator")
        ) { packageName, appLabel ->
            requested = packageName
            label = appLabel
            PluginOutput("Launch approval requested", "Awaiting ONE AI approval")
        }
        assertEquals("com.example.calculator", requested)
        assertEquals("Calculator", label)
        assertEquals("Launch approval requested", output.title)
    }

    @Test fun malformedPackageNeverReachesLaunchBoundary() {
        try {
            AppControlCommands().execute("launch_app", mapOf("package_name" to "not a package")) { _, _ ->
                fail("Invalid package must not reach the Android launch boundary")
                PluginOutput("", "")
            }
            fail("Expected invalid package failure")
        } catch (expected: PluginException) {
            assertEquals("INVALID_PACKAGE", expected.code)
        }
    }

    @Test fun rejectsUndeclaredCommand() {
        try {
            AppControlCommands().execute("delete_app", emptyMap()) { _, _ -> fail("Must not launch"); PluginOutput("", "") }
            fail("Expected unsupported command failure")
        } catch (expected: PluginException) {
            assertEquals("UNKNOWN_COMMAND", expected.code)
        }
    }

    @Test fun bubbleViewListsAppsAsBoundedDeclarativeButtons() {
        val apps = (1..60).map { AppLaunchTarget("com.example.app$it", "Example $it") }
        val root = JSONObject(AppControlBubbleView.render(apps))
        assertEquals(1, root.getInt("v"))
        val elements = root.getJSONArray("elements")
        assertEquals(1 + AppControlBubbleView.MAX_VISIBLE_APPS, elements.length())
        val button = elements.getJSONObject(1)
        assertEquals("button", button.getString("t"))
        assertEquals("launch_app", button.getString("command"))
        assertEquals("com.example.app1", button.getJSONObject("args").getString("package_name"))
        assertEquals("Example 1", button.getJSONObject("args").getString("app_label"))
    }

    @Test fun renderedBubbleViewMatchesTheSharedHostContractFixture() {
        val fixture = File("src/test/resources/app_control_view.json").readText()
        val rendered = AppControlBubbleView.render(listOf(AppLaunchTarget("com.example.calculator", "Calculator")))
        assertTrue("production Bubble JSON must match the fixture consumed by the host parser",
            JSONObject(rendered).similar(JSONObject(fixture)))
    }

    @Test fun emptyCatalogueShowsAnExplicitEmptyState() {
        val elements = JSONObject(AppControlBubbleView.render(emptyList())).getJSONArray("elements")
        assertEquals(2, elements.length())
        assertEquals("No launcher-visible apps were found.", elements.getJSONObject(1).getString("text"))
    }
}
