package com.oneai.app.bubble

import org.junit.Assert.assertEquals
import org.junit.Test

class BubbleUiPolicyTest {
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
        val available = BubbleUiPolicy.availableServices(setOf("one.screen.intelligence"))
        assertEquals(setOf("command_manager", "screen_intelligence"), available)
        assertEquals(
            setOf("command_manager"),
            BubbleUiPolicy.visibleServices(
                setOf("command_manager", "screen_intelligence", "web_research"),
                BubbleUiPolicy.availableServices(emptySet())
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
}
