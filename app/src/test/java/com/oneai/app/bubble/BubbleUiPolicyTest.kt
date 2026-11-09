package com.oneai.app.bubble
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.Assert.assertTrue
import java.io.File
class BubbleUiPolicyTest {
    /** Retained legacy plugin to Bubble service names (see CoreReservedNames); fixed here so Core tests do not depend on any capability code. */
    private val legacyBindings = mapOf(
        "one.web.research" to "web_research",
        "one.screen.intelligence" to "screen_intelligence",
        "one.voice.interface" to "voice_interface",
        "one.automation.engine" to "automation"
    )
    @Test fun sizeIsClampedToSupportedBounds() {
        assertEquals(40, BubbleUiPolicy.safeSize(12))
        assertEquals(58, BubbleUiPolicy.safeSize(58))
        assertEquals(96, BubbleUiPolicy.safeSize(140))
    }
    @Test fun positionIsClampedToVisibleDisplay() {
        assertEquals(
            BubblePosition(0, 0),
            BubbleUiPolicy.clampPosition(-20, -5, 60, 60, 400, 800)
        )
        assertEquals(
            BubblePosition(340, 740),
            BubbleUiPolicy.clampPosition(900, 900, 60, 60, 400, 800)
        )
    }
    @Test fun oversizedBubbleNeverGetsNegativeCoordinates() {
        assertEquals(
            BubblePosition(0, 0),
            BubbleUiPolicy.clampPosition(50, 50, 500, 900, 400, 800)
        )
    }
    @Test fun onlyInstalledEnabledPluginsExposeBubbleServices() {
        val available = BubbleUiPolicy.availableServices(setOf("one.screen.intelligence"), legacyBindings)
        assertEquals(setOf("command_manager", "screen_intelligence"), available)
        assertEquals(
            setOf("command_manager"),
            BubbleUiPolicy.visibleServices(
                setOf("command_manager", "screen_intelligence", "web_research"),
                BubbleUiPolicy.availableServices(emptySet(), legacyBindings)
            )
        )
    }
    @Test fun savedServiceOrderIsFilteredDeduplicatedAndCompletedDeterministically() {
        assertEquals(
            listOf("voice_interface", "command_manager", "web_research"),
            BubbleUiPolicy.orderedServices(
                listOf("voice_interface", "removed_plugin", "voice_interface"),
                setOf("command_manager", "web_research", "voice_interface")
            )
        )
    }
    @Test fun zeroPluginsLeavesOnlyTheCoreCommandService() {
        assertEquals(setOf("command_manager"), BubbleUiPolicy.availableServices(emptySet(), legacyBindings))
        assertEquals(setOf("command_manager"), BubbleUiPolicy.availableServices(emptySet(), emptyMap()))
    }
    @Test fun everyBoundPluginExposesItsServiceOnlyWhenEnabled() {
        legacyBindings.forEach { (pluginId, service) ->
            assertEquals(setOf("command_manager", service), BubbleUiPolicy.availableServices(setOf(pluginId), legacyBindings))
        }
    }
    @Test fun unknownOrUnboundPluginsNeverCreateServices() {
        assertEquals(
            setOf("command_manager"),
            BubbleUiPolicy.availableServices(setOf("dev.example.notes", "one.command.tools"), legacyBindings)
        )
    }
    @Test fun panelStartsEmptyUntilBubbleAccessIsExplicitlyGranted() {
        val bindings = mapOf("dev.example.notes" to "notes")
        assertEquals(emptyList<String>(), BubbleUiPolicy.panelServices(emptySet(), emptySet(), bindings))
        assertEquals(emptyList<String>(), BubbleUiPolicy.panelServices(setOf("dev.example.notes"), emptySet(), bindings))
        assertEquals(emptyList<String>(), BubbleUiPolicy.panelServices(emptySet(), setOf("notes"), bindings))
        assertEquals(listOf("notes"), BubbleUiPolicy.panelServices(setOf("dev.example.notes"), setOf("notes"), bindings))
    }
    @Test fun eachPluginRequiresItsOwnBubbleAccessAndDisabledPluginsAreFiltered() {
        val bindings = mapOf("dev.example.notes" to "notes", "dev.example.tasks" to "tasks")
        assertEquals(listOf("tasks"), BubbleUiPolicy.panelServices(setOf("dev.example.notes", "dev.example.tasks"), setOf("tasks"), bindings))
        assertEquals(listOf("notes"), BubbleUiPolicy.panelServices(setOf("dev.example.notes"), setOf("notes", "tasks"), bindings))
    }
    @Test fun coreCommandManagerIsAlsoOptInAndPanelOrderIsStable() {
        assertEquals(emptyList<String>(), BubbleUiPolicy.panelServices(emptySet(), emptySet(), emptyMap()))
        assertEquals(listOf("tasks", "command_manager", "notes"), BubbleUiPolicy.panelServices(
            setOf("dev.example.notes", "dev.example.tasks"), setOf("command_manager", "notes", "tasks"),
            mapOf("dev.example.notes" to "notes", "dev.example.tasks" to "tasks"), listOf("tasks", "removed", "command_manager", "tasks")
        ))
    }
    @Test fun productionPanelUsesTheTestedPolicyAndRetainsEmptyStateControls() {
        val dir = listOf("src/main/java/com/oneai/app/bubble", "app/src/main/java/com/oneai/app/bubble").map(::File).first { it.isDirectory }
        val source = File(dir, "FloatingBubbleService.kt").readText()
        assertTrue(source.contains("BubbleUiPolicy.panelServices("))
        assertTrue(source.contains("No services enabled. Choose services in ONE AI Settings."))
        assertTrue(!source.contains("addButton(box, \"Resize bubble\")"))
        assertTrue(source.contains("ScaleGestureDetector"))
        assertTrue(source.contains("Gravity.TOP or Gravity.START"))
        assertTrue(source.contains("addButton(box, \"Hide bubble for 1 minute\")"))
        assertTrue(source.contains("addButton(box, \"Close panel\")"))
    }
    @Test fun anyDeclaredBindingWorksWithoutCoreChanges() {
        assertEquals(
            setOf("command_manager", "notes"),
            BubbleUiPolicy.availableServices(setOf("dev.example.notes"), mapOf("dev.example.notes" to "notes"))
        )
    }
    @Test fun coreCommandServiceIsLabelledWithCapitals() {
        assertEquals("Command Manager", BubbleUiPolicy.serviceLabel(BubbleUiPolicy.COMMAND_MANAGER, null))
        assertEquals("Echo", BubbleUiPolicy.serviceLabel("echo", "Echo"))
        assertEquals("Some service", BubbleUiPolicy.serviceLabel("some_service", null))
    }
    @Test fun panelDimensionsAreClampedIndependentlyOfTheBubbleIcon() {\n        assertEquals(180, BubbleUiPolicy.safePanelWidthDp(100, 400))\n        assertEquals(376, BubbleUiPolicy.safePanelWidthDp(900, 400))\n        assertEquals(180, BubbleUiPolicy.safePanelHeightDp(90, 800))\n        assertEquals(752, BubbleUiPolicy.safePanelHeightDp(900, 800))\n    }\n\n    @Test fun panelCapIsAFractionOfTheScreenWithAFloor() {
        assertEquals(780, BubbleUiPolicy.panelMaxHeightPx(1000))
        assertEquals(320, BubbleUiPolicy.panelMaxHeightPx(100))
    }
    @Test fun quickPanelUsesResizableViewportDimensions() {
        val dir = listOf("src/main/java/com/oneai/app/bubble", "app/src/main/java/com/oneai/app/bubble").map(::File).first { it.isDirectory }
        val source = File(dir, "FloatingBubbleService.kt").readText()
        val panel = source.substringAfter("private fun attachPanel").substringBefore("private fun resizeBubble")
        assertTrue(panel.contains("BubbleUiPolicy.safePanelWidthDp"))
        assertTrue(panel.contains("widthPx, panelMaxHeightPx, overlayType()"))
        assertTrue(panel.contains("isFillViewport = true"))
    }
}